package com.codeatlas.analysis.lineage;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic lineage traversal over the model's lineage edges.
 *
 * <p>Depth-first, cycle-safe, depth- and path-capped. Neighbors are visited in a
 * sorted order so two runs over the same model produce byte-identical results.
 * UNRESOLVED edges never extend a path — they are collected as explicit gaps, as
 * are ambiguous DI edges (which do extend paths when inferred edges are allowed)
 * and terminal response DTOs whose consumer is outside the repository.
 */
public final class LineageService {

    /** The edge kinds that carry data-flow meaning for traversal. */
    public static final Set<RelationshipKind> LINEAGE_KINDS = EnumSet.of(
            RelationshipKind.INVOKES, RelationshipKind.CONSUMES, RelationshipKind.PRODUCES,
            RelationshipKind.READS_FROM, RelationshipKind.WRITES_TO, RelationshipKind.MAPS_TO,
            RelationshipKind.PERSISTS_TO, RelationshipKind.VALIDATED_BY, RelationshipKind.MANAGES,
            RelationshipKind.USES);

    private static final int MAX_PATHS = 100;

    public LineageResult trace(SoftwareModel model, LineageQuery query) {
        List<LineageResult.Path> paths = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        TreeMap<String, LineageResult.Gap> gaps = new TreeMap<>();
        boolean[] flags = new boolean[2]; // [cycles, truncated]

        if (model.entity(query.startId()).isEmpty()) {
            return new LineageResult(query.startId(), query.direction(), List.of(), List.of(),
                    List.of(new LineageResult.Gap(query.startId(), LineageResult.Gap.UNRESOLVED_TARGET,
                            "Start entity not found in the current scan")),
                    false, false);
        }

        if (query.direction() == LineageQuery.Direction.DOWNSTREAM
                || query.direction() == LineageQuery.Direction.BOTH) {
            walk(model, query, false, paths, nodeIds, gaps, flags);
        }
        if (query.direction() == LineageQuery.Direction.UPSTREAM
                || query.direction() == LineageQuery.Direction.BOTH) {
            walk(model, query, true, paths, nodeIds, gaps, flags);
        }

        List<LineageResult.Node> nodes = nodeIds.stream().sorted()
                .map(id -> toNode(model, id))
                .toList();
        paths.sort(Comparator.comparing((LineageResult.Path p) -> String.join("|", p.nodeIds())));
        return new LineageResult(query.startId(), query.direction(), nodes, List.copyOf(paths),
                List.copyOf(gaps.values()), flags[0], flags[1]);
    }

    private void walk(SoftwareModel model, LineageQuery query, boolean upstream,
                      List<LineageResult.Path> paths, Set<String> nodeIds,
                      TreeMap<String, LineageResult.Gap> gaps, boolean[] flags) {
        Deque<String> pathNodes = new ArrayDeque<>();
        Deque<LineageResult.Edge> pathEdges = new ArrayDeque<>();
        Set<String> onPath = new HashSet<>();
        pathNodes.addLast(query.startId());
        onPath.add(query.startId());
        nodeIds.add(query.startId());
        dfs(model, query, upstream, pathNodes, pathEdges, onPath, paths, nodeIds, gaps, flags);
    }

    private void dfs(SoftwareModel model, LineageQuery query, boolean upstream,
                     Deque<String> pathNodes, Deque<LineageResult.Edge> pathEdges, Set<String> onPath,
                     List<LineageResult.Path> paths, Set<String> nodeIds,
                     TreeMap<String, LineageResult.Gap> gaps, boolean[] flags) {
        if (paths.size() >= MAX_PATHS) {
            flags[1] = true;
            return;
        }
        String current = pathNodes.peekLast();
        collectGapsAt(model, current, gaps);

        boolean extended = false;
        if (pathEdges.size() < query.maxDepth()) {
            for (Step step : nextSteps(model, current, upstream, query, gaps)) {
                String next = upstream ? step.edge.fromId() : step.edge.toId();
                if (onPath.contains(next)) {
                    flags[0] = true; // cycle cut
                    continue;
                }
                extended = true;
                pathNodes.addLast(next);
                pathEdges.addLast(step.view);
                onPath.add(next);
                nodeIds.add(next);
                dfs(model, query, upstream, pathNodes, pathEdges, onPath, paths, nodeIds, gaps, flags);
                onPath.remove(next);
                pathEdges.removeLast();
                pathNodes.removeLast();
            }
        } else if (!nextSteps(model, current, upstream, query, gaps).isEmpty()) {
            flags[1] = true; // depth limit cut something off
        }

        if (!extended && pathEdges.size() > 0 && paths.size() < MAX_PATHS) {
            double min = pathEdges.stream().mapToDouble(LineageResult.Edge::confidence).min().orElse(1.0);
            paths.add(new LineageResult.Path(List.copyOf(pathNodes), List.copyOf(pathEdges), min));
            terminalGap(model, pathNodes.peekLast(), upstream, gaps);
        }
    }

    /** A qualifying traversal step: the raw edge plus its result view. */
    private record Step(Relationship edge, LineageResult.Edge view) {
    }

    private List<Step> nextSteps(SoftwareModel model, String nodeId, boolean upstream,
                                 LineageQuery query, TreeMap<String, LineageResult.Gap> gaps) {
        List<Relationship> candidates = upstream ? model.incoming(nodeId) : model.outgoing(nodeId);
        List<Step> steps = new ArrayList<>();
        for (Relationship r : candidates) {
            if (!LINEAGE_KINDS.contains(r.kind())) {
                continue;
            }
            if (r.status() == ResolutionStatus.UNRESOLVED) {
                gaps.putIfAbsent(gapKey(r), new LineageResult.Gap(r.fromId(),
                        LineageResult.Gap.UNRESOLVED_TARGET,
                        "Reference to '" + r.toId() + "' could not be resolved"
                                + r.location().map(l -> " (" + l + ")").orElse("")));
                continue; // never extend a path through an unresolved edge
            }
            double confidence = confidenceOf(r);
            if (confidence < query.minConfidence()) {
                continue;
            }
            boolean inferred = r.status() == ResolutionStatus.INFERRED
                    || "true".equals(r.attributes().get(EvidenceKeys.INFERRED));
            if (inferred && !query.includeInferred()) {
                continue;
            }
            if ("true".equals(r.attributes().get(EvidenceKeys.AMBIGUOUS))) {
                gaps.putIfAbsent(gapKey(r), new LineageResult.Gap(r.fromId(),
                        LineageResult.Gap.AMBIGUOUS_IMPLEMENTATION,
                        "Multiple implementations are possible for the call to '" + r.toId()
                                + "'; all candidates are kept and none was chosen"));
            }
            steps.add(new Step(r, toEdgeView(r, confidence, inferred)));
        }
        steps.sort(Comparator
                .comparing((Step s) -> s.edge.kind().name())
                .thenComparing(s -> upstream ? s.edge.fromId() : s.edge.toId()));
        return steps;
    }

    /** Gaps attached to a node itself (explicit UNRESOLVED lineage edges out of it). */
    private void collectGapsAt(SoftwareModel model, String nodeId, TreeMap<String, LineageResult.Gap> gaps) {
        for (Relationship r : model.outgoing(nodeId)) {
            if (LINEAGE_KINDS.contains(r.kind()) && r.status() == ResolutionStatus.UNRESOLVED) {
                gaps.putIfAbsent(gapKey(r), new LineageResult.Gap(nodeId,
                        LineageResult.Gap.UNRESOLVED_TARGET,
                        "Reference to '" + r.toId() + "' could not be resolved"
                                + r.location().map(l -> " (" + l + ")").orElse("")));
            }
        }
    }

    /** A downstream path ending at a response DTO has an external, unconfirmed consumer. */
    private void terminalGap(SoftwareModel model, String terminalId, boolean upstream,
                             TreeMap<String, LineageResult.Gap> gaps) {
        if (upstream) {
            return;
        }
        model.entity(terminalId).ifPresent(e -> {
            if ("dto-response".equals(e.attributes().get(Entity.Attributes.ROLE))) {
                gaps.putIfAbsent("terminal|" + terminalId, new LineageResult.Gap(terminalId,
                        LineageResult.Gap.EXTERNAL_CONSUMER,
                        "The consumer of '" + e.name() + "' is external and is not represented in this repository"));
            }
        });
    }

    private static LineageResult.Edge toEdgeView(Relationship r, double confidence, boolean inferred) {
        return new LineageResult.Edge(r.fromId(), r.toId(), r.kind().name(),
                r.attributes().getOrDefault(EvidenceKeys.RULE_ID, ""),
                confidence, r.status().name(), inferred,
                "true".equals(r.attributes().get(EvidenceKeys.AMBIGUOUS)),
                r.location().map(SourceLocation::toString).orElse(""));
    }

    private static LineageResult.Node toNode(SoftwareModel model, String id) {
        Entity e = model.entity(id).orElse(null);
        if (e == null) {
            return new LineageResult.Node(id, "UNKNOWN", id, "");
        }
        String label = e.kind() == EntityKind.ENDPOINT || e.kind() == EntityKind.DATABASE_OBJECT
                ? e.name() : e.qualifiedName();
        return new LineageResult.Node(id, e.kind().name(), label,
                e.location().map(SourceLocation::toString).orElse(""));
    }

    private static double confidenceOf(Relationship r) {
        try {
            return Double.parseDouble(r.attributes().getOrDefault(EvidenceKeys.CONFIDENCE, "1.0"));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private static String gapKey(Relationship r) {
        return r.kind() + "|" + r.fromId() + "|" + r.toId();
    }
}
