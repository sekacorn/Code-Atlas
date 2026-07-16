package com.codeatlas.onboarding;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;
import static com.codeatlas.onboarding.OnboardingText.isAda;
import static com.codeatlas.onboarding.OnboardingText.isJava;

/**
 * Stage 7: choose a small, deterministic sample of representative data-lineage
 * paths that best teach a new developer how data moves. Candidate starts are the
 * system's real inputs (endpoints, data sources, Ada mains); each is traced
 * downstream, the strongest path from each start is kept, and the sample is ranked
 * by a deterministic score that favours high confidence, paths that cross a
 * boundary or a data store, and Java/Ada coverage. Never a claim to be exhaustive.
 */
final class LineageSampler {

    private static final Set<String> STORE_OR_SINK = Set.of("DATABASE_OBJECT", "VARIABLE", "DATA_SINK");

    private static final List<String> BLIND_SPOTS = List.of(
            "A path is complete only within analyzed evidence",
            "Reflection, dynamic SQL, framework proxies and external configuration may add paths "
                    + "Code Atlas cannot see");

    private final AtlasToolApi api;
    private final OnboardingOptions options;

    LineageSampler(AtlasToolApi api, OnboardingOptions options) {
        this.api = api;
        this.options = options;
    }

    List<RepresentativeLineagePath> select(List<EntryPointSummary> entryPoints,
                                           List<BoundarySummary> boundaries) {
        Set<String> boundaryNodes = new TreeSet<>();
        for (BoundarySummary b : boundaries) {
            boundaryNodes.add(b.javaSideId());
            if (!b.adaSideId().isEmpty()) {
                boundaryNodes.add(b.adaSideId());
            }
        }

        // Deterministic candidate start set: endpoints, data sources, Ada mains.
        Set<String> starts = new TreeSet<>();
        api.searchEntities("", "ENDPOINT", null, CANDIDATE_CAP).value().forEach(e -> starts.add(e.stableId()));
        api.getDataSources().value().forEach(e -> starts.add(e.stableId()));
        entryPoints.stream().filter(e -> e.isMain() && e.language().equals("ada"))
                .forEach(e -> starts.add(e.stableId()));

        List<Scored> scored = new ArrayList<>();
        for (String start : starts) {
            LineageResult result = api.traceDataLineage(new LineageQuery(start,
                    LineageQuery.Direction.DOWNSTREAM, 8, options.includeInferred(),
                    options.minConfidence())).value();
            LineageResult.Path best = bestPath(result);
            if (best == null || best.nodeIds().size() < 2) {
                continue;
            }
            scored.add(new Scored(start, result, best, score(best, boundaryNodes)));
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).reversed()
                .thenComparing(s -> s.startId));

        // Guarantee Java and Ada representation when both exist, then fill by score.
        int max = options.maxPathsOrDefault();
        LinkedHashSet<Scored> chosen = new LinkedHashSet<>();
        scored.stream().filter(s -> isJava(s.startId)).findFirst().ifPresent(chosen::add);
        scored.stream().filter(s -> isAda(s.startId)).findFirst().ifPresent(chosen::add);
        for (Scored s : scored) {
            if (chosen.size() >= max) {
                break;
            }
            chosen.add(s);
        }
        List<RepresentativeLineagePath> out = new ArrayList<>();
        chosen.stream().limit(max).forEach(s -> out.add(toPath(s)));
        out.sort(Comparator.comparing(RepresentativeLineagePath::startId)
                .thenComparing(RepresentativeLineagePath::endId));
        return List.copyOf(out);
    }

    /** The strongest downstream path: prefer ending at a store/sink, then length, then confidence. */
    private LineageResult.Path bestPath(LineageResult result) {
        return result.paths().stream()
                .max(Comparator.comparingInt((LineageResult.Path p) -> endsAtStore(result, p) ? 1 : 0)
                        .thenComparingInt(p -> p.nodeIds().size())
                        .thenComparingDouble(LineageResult.Path::minConfidence)
                        .thenComparing(p -> p.nodeIds().get(p.nodeIds().size() - 1),
                                Comparator.reverseOrder()))
                .orElse(null);
    }

    private boolean endsAtStore(LineageResult result, LineageResult.Path p) {
        String terminal = p.nodeIds().get(p.nodeIds().size() - 1);
        return STORE_OR_SINK.contains(kindOf(result, terminal));
    }

    private int score(LineageResult.Path p, Set<String> boundaryNodes) {
        int s = (int) Math.round(p.minConfidence() * 100);
        s += Math.min(p.nodeIds().size(), 8) * 4;                 // longer, richer paths
        if (p.nodeIds().stream().anyMatch(boundaryNodes::contains)) {
            s += 30;                                              // crosses a boundary component
        }
        boolean java = p.nodeIds().stream().anyMatch(OnboardingText::isJava);
        boolean ada = p.nodeIds().stream().anyMatch(OnboardingText::isAda);
        if (java && ada) {
            s += 25;                                              // spans Java and Ada
        }
        return s;
    }

    private RepresentativeLineagePath toPath(Scored s) {
        LineageResult.Path p = s.best;
        String start = p.nodeIds().get(0);
        String end = p.nodeIds().get(p.nodeIds().size() - 1);
        List<String> edges = new ArrayList<>();
        List<EvidenceRef> evidence = new ArrayList<>();
        TreeSet<String> kinds = new TreeSet<>();
        for (LineageResult.Edge e : p.edges()) {
            edges.add(shortNode(e.fromId()) + " -[" + e.kind() + "]-> " + shortNode(e.toId()));
            kinds.add(e.kind());
            evidence.add(new EvidenceRef(e.fromId(), e.location().isBlank() ? e.ruleId() : e.location()));
        }
        List<String> gaps = new ArrayList<>();
        Set<String> onPath = new TreeSet<>(p.nodeIds());
        for (LineageResult.Gap g : s.result.gaps()) {
            if (onPath.contains(g.atId())) {
                gaps.add("[" + g.kind() + "] " + g.description());
            }
        }
        boolean partial = !gaps.isEmpty() || s.result.truncated();
        String title = labelOf(s.result, start) + " -> ... -> " + labelOf(s.result, end);
        return new RepresentativeLineagePath(title, start, end, p.nodeIds(), edges,
                new ArrayList<>(kinds), OnboardingText.band(p.minConfidence()), evidence,
                gaps, BLIND_SPOTS, partial);
    }

    private static String kindOf(LineageResult result, String nodeId) {
        return result.nodes().stream().filter(n -> n.id().equals(nodeId))
                .map(LineageResult.Node::kind).findFirst().orElse("");
    }

    private static String labelOf(LineageResult result, String nodeId) {
        return result.nodes().stream().filter(n -> n.id().equals(nodeId))
                .map(LineageResult.Node::label).findFirst().orElse(OnboardingText.shortId(nodeId));
    }

    private static String shortNode(String id) {
        return OnboardingText.shortId(id);
    }

    /** A scored candidate path, kept with its source result for gap extraction. */
    private record Scored(String startId, LineageResult result, LineageResult.Path best, int score) {
    }
}
