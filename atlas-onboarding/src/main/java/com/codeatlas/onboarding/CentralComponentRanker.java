package com.codeatlas.onboarding;

import com.codeatlas.model.Entity;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EntryPointCategory;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 8: rank the components a new developer should study first, using a
 * deterministic multi-signal score - never one metric alone. Signals: fan-in,
 * fan-out, participation in the sampled lineage paths, proximity to an entry
 * point, data-store access, participation in a Java/Ada boundary, complexity, and
 * unresolved-dependency count. Ties break on stable id. Each selected component
 * gets a structured, evidence-cited summary.
 */
final class CentralComponentRanker {

    private static final Set<String> DATA_EDGE_KINDS =
            Set.of("WRITES_TO", "PERSISTS_TO", "MAPS_TO", "READS_FROM");

    private static final List<String> LIMITATIONS = List.of(
            "Centrality counts resolved edges only; unresolved references are reported separately",
            "Responsibility is inferred from roles/annotations, not documentation or runtime behavior");

    private final AtlasToolApi api;
    private final OnboardingOptions options;

    CentralComponentRanker(AtlasToolApi api, OnboardingOptions options) {
        this.api = api;
        this.options = options;
    }

    List<CentralComponentSummary> rank(List<EntryPointSummary> entryPoints,
                                       List<BoundarySummary> boundaries,
                                       List<RepresentativeLineagePath> lineagePaths) {
        Set<String> entryOwners = entryOwnerIds(entryPoints);
        Set<String> boundaryNodes = new TreeSet<>();
        boundaries.forEach(b -> {
            boundaryNodes.add(b.javaSideId());
            if (!b.adaSideId().isEmpty()) {
                boundaryNodes.add(b.adaSideId());
            }
        });
        Set<String> lineageNodes = new TreeSet<>();
        lineagePaths.forEach(p -> lineageNodes.addAll(p.orderedNodes()));
        Set<String> unresolvedFrom = new TreeSet<>();
        api.getUnresolvedReferences(CANDIDATE_CAP).value().forEach(u -> unresolvedFrom.add(u.fromId()));

        List<Views.EntityView> candidates = new ArrayList<>();
        for (String kind : List.of("CLASS", "INTERFACE", "RECORD", "ENUM")) {
            candidates.addAll(api.searchEntities("", kind, "java", CANDIDATE_CAP).value());
        }
        candidates.addAll(api.searchEntities("", "PACKAGE", "ada", CANDIDATE_CAP).value());

        List<Scored> scored = new ArrayList<>();
        for (Views.EntityView c : candidates) {
            scored.add(score(c, entryOwners, boundaryNodes, lineageNodes, unresolvedFrom));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).reversed()
                .thenComparing(s -> s.entity.stableId()));

        List<CentralComponentSummary> out = new ArrayList<>();
        scored.stream().filter(s -> s.score > 0).limit(options.maxComponentsOrDefault())
                .forEach(s -> out.add(summarize(s)));
        return List.copyOf(out);
    }

    private Scored score(Views.EntityView c, Set<String> entryOwners, Set<String> boundaryNodes,
                         Set<String> lineageNodes, Set<String> unresolvedFrom) {
        int fanIn = api.getDependents(c.stableId(), 1).totalMatches();
        int fanOut = api.getDependencies(c.stableId(), 1).totalMatches();
        List<Views.NeighborView> members = api.getMembers(c.stableId(), CANDIDATE_CAP).value();
        Set<String> memberIds = new TreeSet<>();
        members.forEach(m -> memberIds.add(m.entity().stableId()));
        int maxComplexity = members.stream()
                .mapToInt(m -> parseInt(m.entity().attributes().get(Entity.Attributes.CYCLOMATIC_COMPLEXITY)))
                .max().orElse(0);

        boolean entryProximate = entryOwners.contains(c.stableId())
                || memberIds.stream().anyMatch(entryOwners::contains);
        boolean boundaryParticipant = boundaryNodes.contains(c.stableId())
                || memberIds.stream().anyMatch(boundaryNodes::contains);
        boolean onLineage = lineageNodes.contains(c.stableId())
                || memberIds.stream().anyMatch(lineageNodes::contains);
        boolean dataAccess = api.getDependencies(c.stableId(), CANDIDATE_CAP).value().stream()
                .anyMatch(n -> n.entity().kind().equals("DATABASE_OBJECT")
                        || DATA_EDGE_KINDS.contains(n.edge().kind()));
        int unresolved = (int) memberIds.stream().filter(unresolvedFrom::contains).count()
                + (unresolvedFrom.contains(c.stableId()) ? 1 : 0);

        // These weights rank reading value, not defect severity; caps limit outliers.
        int score = fanIn * 5 + fanOut * 2
                + (onLineage ? 8 : 0)
                + (entryProximate ? 10 : 0)
                + (dataAccess ? 6 : 0)
                + (boundaryParticipant ? 12 : 0)
                + Math.min(maxComplexity, 20)
                + Math.min(unresolved, 5);

        List<String> basis = new ArrayList<>();
        basis.add("fan-in " + fanIn);
        basis.add("fan-out " + fanOut);
        if (onLineage) {
            basis.add("on a sampled lineage path");
        }
        if (entryProximate) {
            basis.add("hosts an entry point");
        }
        if (dataAccess) {
            basis.add("accesses a data store");
        }
        if (boundaryParticipant) {
            basis.add("participates in a Java/Ada boundary");
        }
        if (maxComplexity > 0) {
            basis.add("max complexity " + maxComplexity);
        }
        if (unresolved > 0) {
            basis.add(unresolved + " unresolved dependency(ies)");
        }
        return new Scored(c, members, maxComplexity, score, String.join(", ", basis));
    }

    private CentralComponentSummary summarize(Scored s) {
        Views.EntityView c = s.entity;
        List<String> callers = new ArrayList<>();
        List<String> dependents = new ArrayList<>();
        api.getDependents(c.stableId(), 8).value().forEach(n -> {
            callers.add(n.entity().qualifiedName() + " (" + n.edge().kind().toLowerCase() + ")");
            dependents.add(n.entity().qualifiedName());
        });
        List<String> callees = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        List<String> dataSinks = new ArrayList<>();
        List<String> dataSources = new ArrayList<>();
        List<String> sideEffects = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        for (Views.NeighborView n : api.getDependencies(c.stableId(), CANDIDATE_CAP).value()) {
            String kind = n.edge().kind();
            String qn = n.entity().qualifiedName();
            callees.add(qn + " (" + kind.toLowerCase() + ")");
            dependencies.add(qn);
            switch (kind) {
                case "WRITES_TO", "PERSISTS_TO" -> {
                    dataSinks.add(qn);
                    sideEffects.add("writes to " + qn);
                }
                case "MAPS_TO" -> dataSinks.add(qn);
                case "READS_FROM" -> dataSources.add(qn);
                case "CONSUMES" -> inputs.add(qn);
                case "PRODUCES" -> outputs.add(qn);
                default -> {
                }
            }
        }
        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(OnboardingText.ref(c));
        s.members.stream().limit(4).forEach(m ->
                evidence.add(new EvidenceRef(m.entity().stableId(), m.entity().location())));

        return new CentralComponentSummary(c.stableId(), c.qualifiedName(),
                OnboardingText.languageOf(c.stableId()), Responsibilities.of(c, s.members),
                dedupSorted(inputs), dedupSorted(outputs), cap(callers), cap(callees),
                dedupSorted(dependencies), dedupSorted(dependents), dedupSorted(dataSources),
                dedupSorted(dataSinks), dedupSorted(sideEffects), s.maxComplexity, s.score,
                s.scoreBasis, evidence, LIMITATIONS);
    }

    // ---- helpers ----

    /** The owner-entity ids that "host" an entry point (a main's class, an endpoint's handler class). */
    private Set<String> entryOwnerIds(List<EntryPointSummary> entryPoints) {
        Set<String> owners = new TreeSet<>();
        for (EntryPointSummary e : entryPoints) {
            owners.add(e.stableId());
            if (e.stableId().startsWith("java:method:") || e.stableId().startsWith("java:constructor:")) {
                owners.add(typeIdOf(e.stableId()));
            }
            // An endpoint's handler is its second evidence ref; its owner class hosts it.
            if (e.category() == EntryPointCategory.ENDPOINT && e.evidence().size() > 1) {
                String handler = e.evidence().get(1).stableId();
                if (handler.startsWith("java:method:")) {
                    owners.add(typeIdOf(handler));
                }
            }
        }
        return owners;
    }

    private static String typeIdOf(String methodOrCtorId) {
        int firstColon = methodOrCtorId.indexOf(':', methodOrCtorId.indexOf(':') + 1);
        String qn = firstColon >= 0 ? methodOrCtorId.substring(firstColon + 1) : methodOrCtorId;
        int hash = qn.indexOf('#');
        if (hash >= 0) {
            qn = qn.substring(0, hash);
        }
        return "java:type:" + qn;
    }

    private static List<String> cap(List<String> list) {
        return list.stream().distinct().sorted().limit(8).toList();
    }

    private static List<String> dedupSorted(List<String> list) {
        return list.stream().distinct().sorted().limit(8).toList();
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record Scored(Views.EntityView entity, List<Views.NeighborView> members,
                          int maxComplexity, int score, String scoreBasis) {
    }
}
