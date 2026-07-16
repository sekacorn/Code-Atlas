package com.codeatlas.onboarding;

import com.codeatlas.model.Entity;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.BoundaryType;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;
import static com.codeatlas.onboarding.OnboardingText.isJava;
import static com.codeatlas.onboarding.OnboardingText.normalizeName;
import static com.codeatlas.onboarding.OnboardingText.ref;

/**
 * Stage 6: discover Java<->Ada (and Java<->non-Java) boundaries from real crossing
 * evidence - never from name similarity alone.
 *
 * <p>A boundary is emitted only when the model carries a genuine crossing signal:
 * <ul>
 *   <li>a Java {@code native} (JNI) method - a direct boundary to non-Java code;</li>
 *   <li>a Java reference to a process / message / network API (an indirect boundary,
 *       gated on the repository actually containing Ada);</li>
 *   <li>a real unresolved Java reference whose name matches a declared Ada
 *       subprogram (an inferred boundary - the reference is real, the counterpart
 *       is name-matched and clearly labelled as such);</li>
 *   <li>a Java entity and an Ada entity that read/write the <em>same</em> data-store
 *       identity (shared data).</li>
 * </ul>
 * Two entities that merely share a similar name, with no crossing edge or marker,
 * never produce a boundary. Every counterpart reached by name match is reported as
 * INFERRED with its missing information stated.
 */
final class BoundaryDetector {

    private static final Set<String> PROCESS_TOKENS =
            Set.of("processbuilder", "runtime", "exec", "processimpl", "process");
    private static final Set<String> MESSAGE_TOKENS =
            Set.of("jmstemplate", "kafkatemplate", "rabbittemplate", "amqptemplate",
                    "send", "publish", "convertandsend", "enqueue", "produce");
    private static final Set<String> NETWORK_TOKENS =
            Set.of("socket", "serversocket", "httpclient", "urlconnection", "resttemplate", "webclient");

    private final AtlasToolApi api;

    BoundaryDetector(AtlasToolApi api) {
        this.api = api;
    }

    List<BoundarySummary> detect(List<EntryPointSummary> entryPoints) {
        // Ada subprogram index by normalized simple name (for conservative matching).
        Map<String, List<Views.EntityView>> adaByName = new TreeMap<>();
        List<Views.EntityView> adaSubprograms = new ArrayList<>();
        adaSubprograms.addAll(api.searchEntities("", "PROCEDURE", "ada", CANDIDATE_CAP).value());
        adaSubprograms.addAll(api.searchEntities("", "FUNCTION", "ada", CANDIDATE_CAP).value());
        for (Views.EntityView s : adaSubprograms) {
            adaByName.computeIfAbsent(normalizeName(s.name()), k -> new ArrayList<>()).add(s);
        }
        boolean hasAda = !adaSubprograms.isEmpty()
                || !api.searchEntities("", "PACKAGE", "ada", 1).value().isEmpty();
        List<EvidenceRef> adaMainRefs = entryPoints.stream()
                .filter(e -> e.isMain() && e.language().equals("ada"))
                .map(e -> new EvidenceRef(e.stableId(), e.location())).toList();

        List<BoundarySummary> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        nativeBoundaries(out, seen, adaByName);
        referenceBoundaries(out, seen, adaByName, hasAda, adaMainRefs);
        sharedDataBoundaries(out, seen);

        out.sort(Comparator.comparingInt((BoundarySummary b) -> b.type().ordinal())
                .thenComparing(BoundarySummary::javaSideId)
                .thenComparing(BoundarySummary::adaSideId)
                .thenComparing(BoundarySummary::sharedArtifact));
        return List.copyOf(out);
    }

    /** Java {@code native} methods: a direct JNI boundary to non-Java code. */
    private void nativeBoundaries(List<BoundarySummary> out, Set<String> seen,
                                  Map<String, List<Views.EntityView>> adaByName) {
        for (Views.EntityView m : api.searchEntities("", "METHOD", "java", CANDIDATE_CAP).value()) {
            if (!"true".equals(m.attributes().get(Entity.Attributes.NATIVE_METHOD))) {
                continue;
            }
            Views.EntityView adaMatch = uniqueMatch(adaByName, m.name());
            List<EvidenceRef> evidence = new ArrayList<>();
            evidence.add(ref(m));
            if (adaMatch != null) {
                evidence.add(ref(adaMatch));
                addBoundary(out, seen, BoundaryType.DIRECT_BOUNDARY, m.stableId(), m.qualifiedName(),
                        adaMatch.stableId(), adaMatch.qualifiedName(),
                        "JNI native method '" + m.name() + "'", evidence,
                        "Low - a native (JNI) method is a direct boundary; the Ada counterpart is "
                                + "name-matched, not resolved (Code Atlas does not link across languages)",
                        "Confirm the JNI implementation is backed by " + adaMatch.qualifiedName()
                                + " and not another native library.",
                        "INFERRED");
            } else {
                addBoundary(out, seen, BoundaryType.UNRESOLVED_BOUNDARY, m.stableId(), m.qualifiedName(),
                        "", "unresolved (native implementation not in analyzed sources)",
                        "JNI native method '" + m.name() + "'", evidence,
                        "Medium - a native (JNI) method is a real boundary to non-Java code",
                        "The native implementation is outside the analyzed sources; identify the "
                                + "library (and whether it is the Ada side) with the build/JNI configuration.",
                        "UNRESOLVED");
            }
        }
    }

    /** Java references to process/message/network APIs, and real unresolved
     *  references whose name matches an Ada subprogram. */
    private void referenceBoundaries(List<BoundarySummary> out, Set<String> seen,
                                     Map<String, List<Views.EntityView>> adaByName,
                                     boolean hasAda, List<EvidenceRef> adaMainRefs) {
        for (Views.UnresolvedReference u : api.getUnresolvedReferences(CANDIDATE_CAP).value()) {
            if (!isJava(u.fromId())) {
                continue;
            }
            String simple = lastSegment(u.targetName());
            String norm = normalizeName(simple);
            Views.EntityView owner = api.getEntity(u.fromId()).value().orElse(null);
            String javaLabel = owner != null ? owner.qualifiedName() : u.fromId();
            List<EvidenceRef> evidence = new ArrayList<>(List.of(new EvidenceRef(u.fromId(), u.location())));

            if (hasAda && PROCESS_TOKENS.contains(norm)) {
                List<EvidenceRef> ev = withCandidates(evidence, adaMainRefs);
                addBoundary(out, seen, BoundaryType.PROCESS_BOUNDARY, u.fromId(), javaLabel,
                        adaMainCandidateId(adaMainRefs), adaMainCandidateLabel(adaMainRefs),
                        "external process invocation ('" + u.targetName() + "')", ev,
                        "Low - a process launch is a boundary; the launched command is not "
                                + "identified statically",
                        "Confirm whether this process invokes the Ada side (e.g. a GNAT main).",
                        adaMainRefs.isEmpty() ? "UNRESOLVED" : "INFERRED");
            } else if (hasAda && MESSAGE_TOKENS.contains(norm)) {
                addBoundary(out, seen, BoundaryType.MESSAGE_BOUNDARY, u.fromId(), javaLabel,
                        "", "external message channel",
                        "messaging API reference ('" + u.targetName() + "')", evidence,
                        "Low - a messaging call is a boundary; the peer is not identified statically",
                        "Identify the destination channel/topic and whether the Ada side consumes it.",
                        "UNRESOLVED");
            } else if (hasAda && NETWORK_TOKENS.contains(norm)) {
                addBoundary(out, seen, BoundaryType.NETWORK_BOUNDARY, u.fromId(), javaLabel,
                        "", "external network endpoint",
                        "network API reference ('" + u.targetName() + "')", evidence,
                        "Low - a network call is a boundary; the endpoint is not identified statically",
                        "Identify the network endpoint and whether the Ada side serves it.",
                        "UNRESOLVED");
            } else {
                Views.EntityView adaMatch = uniqueMatch(adaByName, simple);
                if (adaMatch != null) {
                    evidence.add(ref(adaMatch));
                    addBoundary(out, seen, BoundaryType.INFERRED_BOUNDARY, u.fromId(), javaLabel,
                            adaMatch.stableId(), adaMatch.qualifiedName(),
                            "cross-language reference '" + u.targetName() + "'", evidence,
                            "Low - a real Java reference name-matches an Ada subprogram; "
                                    + "cross-language symbol resolution is not performed",
                            "Confirm this is a genuine integration and not a coincidental name match.",
                            "INFERRED");
                }
            }
        }
    }

    /** A Java entity and an Ada entity that touch the same data-store identity. */
    private void sharedDataBoundaries(List<BoundarySummary> out, Set<String> seen) {
        List<Views.EntityView> stores = new ArrayList<>();
        stores.addAll(api.searchEntities("", "DATABASE_OBJECT", null, CANDIDATE_CAP).value());
        stores.addAll(api.searchEntities("", "VARIABLE", "ada", CANDIDATE_CAP).value());
        for (Views.EntityView store : stores) {
            List<Views.NeighborView> touchers = api.getDependents(store.stableId(), CANDIDATE_CAP).value();
            Views.EntityView java = touchers.stream().map(Views.NeighborView::entity)
                    .filter(e -> isJava(e.stableId())).sorted(Comparator.comparing(Views.EntityView::stableId))
                    .findFirst().orElse(null);
            Views.EntityView ada = touchers.stream().map(Views.NeighborView::entity)
                    .filter(e -> OnboardingText.isAda(e.stableId()))
                    .sorted(Comparator.comparing(Views.EntityView::stableId)).findFirst().orElse(null);
            if (java != null && ada != null) {
                addBoundary(out, seen, BoundaryType.SHARED_DATA_BOUNDARY, java.stableId(), java.qualifiedName(),
                        ada.stableId(), ada.qualifiedName(),
                        "shared data store '" + store.name() + "'",
                        List.of(ref(java), ref(store), ref(ada)),
                        "Medium - both sides reference the same data-store identity",
                        "Confirm the read/write ordering and whether access is coordinated.",
                        "DISCOVERED");
            }
        }
    }

    // ---- helpers ----

    private void addBoundary(List<BoundarySummary> out, Set<String> seen, BoundaryType type,
                             String javaId, String javaLabel, String adaId, String adaLabel,
                             String artifact, List<EvidenceRef> evidence, String confidence,
                             String missing, String resolution) {
        String key = type + "|" + javaId + "|" + adaId + "|" + artifact;
        if (seen.add(key)) {
            out.add(new BoundarySummary(type, javaId, javaLabel, adaId, adaLabel, artifact,
                    List.copyOf(evidence), confidence, missing, resolution));
        }
    }

    private static List<EvidenceRef> withCandidates(List<EvidenceRef> base, List<EvidenceRef> candidates) {
        List<EvidenceRef> all = new ArrayList<>(base);
        all.addAll(candidates);
        return all;
    }

    private static String adaMainCandidateId(List<EvidenceRef> adaMainRefs) {
        return adaMainRefs.isEmpty() ? "" : adaMainRefs.get(0).stableId();
    }

    private static String adaMainCandidateLabel(List<EvidenceRef> adaMainRefs) {
        return adaMainRefs.isEmpty() ? "external process (target not in analyzed sources)"
                : "candidate: " + OnboardingText.shortId(adaMainRefs.get(0).stableId()) + " (inferred)";
    }

    private static Views.EntityView uniqueMatch(Map<String, List<Views.EntityView>> adaByName, String name) {
        List<Views.EntityView> matches = adaByName.get(normalizeName(name));
        return matches != null && matches.size() == 1 ? matches.get(0) : null;
    }

    private static String lastSegment(String name) {
        String s = name;
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
