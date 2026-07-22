package com.codeatlas.graph;

import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.analysis.ComponentDependency;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a laid-out {@link GraphModel} deterministically from the software model
 * and analysis. Node order, edge order and layer assignment depend only on stable
 * ids and content, so identical input yields an identical graph.
 */
public final class GraphBuilder {

    /** Node cap to keep exports legible; larger graphs are truncated with a note. */
    public static final int MAX_NODES = 200;

    private static final Set<RelationshipKind> CALL_KINDS =
            EnumSet.of(RelationshipKind.CALLS, RelationshipKind.INVOKES);
    private static final Set<RelationshipKind> USAGE = EnumSet.of(
            RelationshipKind.CALLS, RelationshipKind.INVOKES, RelationshipKind.REFERENCES,
            RelationshipKind.INHERITS, RelationshipKind.IMPLEMENTS, RelationshipKind.INSTANTIATES,
            RelationshipKind.USES, RelationshipKind.CONSUMES, RelationshipKind.PRODUCES,
            RelationshipKind.READS_FROM, RelationshipKind.WRITES_TO, RelationshipKind.MAPS_TO,
            RelationshipKind.PERSISTS_TO, RelationshipKind.MANAGES, RelationshipKind.CONFIGURES);

    private final SoftwareModel model;
    private final AnalysisResult analysis;

    public GraphBuilder(SoftwareModel model, AnalysisResult analysis) {
        this.model = model;
        this.analysis = analysis;
    }

    public GraphModel build(GraphType type) {
        return switch (type) {
            case DEPENDENCY -> dependency();
            case CALL -> call();
            case DEAD_CODE -> deadCode();
            case ARCHITECTURE -> architecture();
        };
    }

    // ---- dependency graph (package coupling) ----

    private GraphModel dependency() {
        List<ComponentDependency> comps = analysis.dependencies().components();
        Map<String, GraphModel.Node> nodes = new LinkedHashMap<>();
        List<GraphModel.Edge> edges = new ArrayList<>();
        Map<String, List<String>> adj = new TreeMap<>();

        for (ComponentDependency c : comps) {
            adj.put(c.name(), new ArrayList<>(new java.util.TreeSet<>(c.dependsOn())));
        }
        Map<String, Integer> layers = layerByLongestPath(adj);
        for (ComponentDependency c : comps) {
            nodes.put(c.name(), new GraphModel.Node(c.name(), c.name(),
                    riskCategory(c.risk()), layers.getOrDefault(c.name(), 0)));
        }
        for (ComponentDependency c : comps) {
            for (String dep : adj.getOrDefault(c.name(), List.of())) {
                if (nodes.containsKey(dep)) {
                    edges.add(new GraphModel.Edge(c.name(), dep, ""));
                }
            }
        }
        return finalize("Package dependency graph", nodes, edges);
    }

    // ---- call graph ----

    private GraphModel call() {
        Map<String, List<String>> adj = new TreeMap<>();
        Set<String> involved = new HashSet<>();
        Set<EdgeKey> seen = new HashSet<>();
        List<GraphModel.Edge> rawEdges = new ArrayList<>();
        for (Relationship r : sortedRelationships()) {
            if (r.resolved() && CALL_KINDS.contains(r.kind())
                    && isCallable(r.fromId()) && isCallable(r.toId())) {
                if (seen.add(new EdgeKey(r.fromId(), r.toId()))) {
                    rawEdges.add(new GraphModel.Edge(r.fromId(), r.toId(), ""));
                    adj.computeIfAbsent(r.fromId(), k -> new ArrayList<>()).add(r.toId());
                    involved.add(r.fromId());
                    involved.add(r.toId());
                }
            }
        }
        Map<String, Integer> layers = layerByLongestPath(adj);
        Map<String, GraphModel.Node> nodes = new LinkedHashMap<>();
        for (String id : new java.util.TreeSet<>(involved)) {
            nodes.put(id, new GraphModel.Node(id, labelOf(id), GraphModel.Category.DEFAULT,
                    layers.getOrDefault(id, 0)));
        }
        return finalize("Call graph (resolved calls)", nodes, filterEdges(rawEdges, nodes));
    }

    private record EdgeKey(String from, String to) {
    }

    // ---- dead-code graph ----

    private GraphModel deadCode() {
        Set<String> dead = new HashSet<>();
        analysis.deadCode().forEach(c -> dead.add(c.stableId()));

        Set<EntityKind> shown = EnumSet.of(EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM,
                EntityKind.RECORD, EntityKind.METHOD, EntityKind.FUNCTION, EntityKind.PROCEDURE);
        Map<String, GraphModel.Node> nodes = new LinkedHashMap<>();
        for (Entity e : sortedEntities()) {
            if (shown.contains(e.kind())) {
                nodes.put(e.id(), new GraphModel.Node(e.id(), labelOf(e.id()),
                        dead.contains(e.id()) ? GraphModel.Category.DEAD : GraphModel.Category.ACTIVE, 0));
            }
        }
        List<GraphModel.Edge> edges = new ArrayList<>();
        for (Relationship r : sortedRelationships()) {
            if (r.resolved() && USAGE.contains(r.kind())
                    && nodes.containsKey(r.fromId()) && nodes.containsKey(r.toId())) {
                edges.add(new GraphModel.Edge(r.fromId(), r.toId(), ""));
            }
        }
        // Layer by usage depth so isolated (probable-dead) nodes stand apart.
        Map<String, List<String>> adj = new TreeMap<>();
        edges.forEach(e -> adj.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()));
        Map<String, Integer> layers = layerByLongestPath(adj);
        Map<String, GraphModel.Node> layered = new LinkedHashMap<>();
        nodes.forEach((id, n) -> layered.put(id,
                new GraphModel.Node(id, n.label(), n.category(), layers.getOrDefault(id, 0))));
        return finalize("Dead-code graph (active vs probable-dead)", layered, edges);
    }

    // ---- architecture graph (role layers) ----

    private static final Map<String, Integer> ROLE_LAYER = Map.ofEntries(
            Map.entry("ENDPOINT", 0), Map.entry("SOURCE", 0),
            Map.entry("controller", 1),
            Map.entry("service", 2),
            Map.entry("mapper-interface", 3), Map.entry("validator", 3),
            Map.entry("repository", 4),
            Map.entry("TABLE", 5), Map.entry("SINK", 5), Map.entry("STATE", 5));

    private GraphModel architecture() {
        Map<String, GraphModel.Node> nodes = new LinkedHashMap<>();
        for (Entity e : sortedEntities()) {
            String role = architectureRole(e);
            if (role == null) {
                continue;
            }
            nodes.put(e.id(), new GraphModel.Node(e.id(), labelOf(e.id()),
                    architectureCategory(e), ROLE_LAYER.getOrDefault(role, 2)));
        }
        List<GraphModel.Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Relationship r : sortedRelationships()) {
            if (r.resolved() && USAGE.contains(r.kind())
                    && nodes.containsKey(r.fromId()) && nodes.containsKey(r.toId())
                    && seen.add(r.fromId() + "->" + r.toId())) {
                edges.add(new GraphModel.Edge(r.fromId(), r.toId(), ""));
            }
        }
        return finalize("Architecture graph (role layers)", nodes, edges);
    }

    // ---- shared helpers ----

    /** Applies the node cap deterministically and packages the result. */
    private GraphModel finalize(String title, Map<String, GraphModel.Node> nodes,
                                List<GraphModel.Edge> edges) {
        if (nodes.isEmpty()) {
            return new GraphModel(title, List.of(), List.of(), false,
                    "No nodes for this graph in the current scan.");
        }
        boolean truncated = false;
        List<GraphModel.Node> kept = new ArrayList<>(nodes.values());
        if (kept.size() > MAX_NODES) {
            // Keep the lowest-id nodes deterministically; drop the rest.
            kept.sort(Comparator.comparing(GraphModel.Node::id));
            kept = new ArrayList<>(kept.subList(0, MAX_NODES));
            truncated = true;
        }
        Set<String> keptIds = new HashSet<>();
        kept.forEach(n -> keptIds.add(n.id()));
        List<GraphModel.Edge> keptEdges = edges.stream()
                .filter(e -> keptIds.contains(e.from()) && keptIds.contains(e.to()))
                .sorted(Comparator.comparing(GraphModel.Edge::from).thenComparing(GraphModel.Edge::to))
                .distinct().toList();
        String note = truncated
                ? "Graph truncated to " + MAX_NODES + " of " + nodes.size()
                + " nodes; use --format dot and Graphviz for the full graph."
                : "";
        return new GraphModel(title, kept, keptEdges, truncated, note);
    }

    private List<GraphModel.Edge> filterEdges(List<GraphModel.Edge> edges, Map<String, GraphModel.Node> nodes) {
        return edges.stream().filter(e -> nodes.containsKey(e.from()) && nodes.containsKey(e.to())).toList();
    }

    /** Layer = length of the longest path of edges ending at the node (cycles cut). */
    private Map<String, Integer> layerByLongestPath(Map<String, List<String>> adj) {
        // Build reverse adjacency to compute longest incoming path.
        Map<String, List<String>> incoming = new TreeMap<>();
        adj.forEach((from, tos) -> tos.forEach(to ->
                incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(from)));
        Map<String, Integer> layer = new HashMap<>();
        Set<String> nodes = new java.util.TreeSet<>();
        nodes.addAll(adj.keySet());
        incoming.keySet().forEach(nodes::add);
        adj.values().forEach(nodes::addAll);
        for (String n : nodes) {
            longestPath(n, incoming, layer, new HashSet<>());
        }
        return layer;
    }

    private int longestPath(String node, Map<String, List<String>> incoming,
                            Map<String, Integer> memo, Set<String> onPath) {
        Integer cached = memo.get(node);
        if (cached != null) {
            return cached;
        }
        if (!onPath.add(node)) {
            return 0; // cycle: treat back-edge as depth 0
        }
        int best = 0;
        for (String pred : new java.util.TreeSet<>(incoming.getOrDefault(node, List.of()))) {
            best = Math.max(best, 1 + longestPath(pred, incoming, memo, onPath));
        }
        onPath.remove(node);
        // Cache only after unwinding; a cycle cut applies to the current path only.
        memo.put(node, best);
        return best;
    }

    private List<Entity> sortedEntities() {
        return model.entities().stream().sorted(Comparator.comparing(Entity::id)).toList();
    }

    private List<Relationship> sortedRelationships() {
        return model.relationships().stream()
                .sorted(Comparator.comparing(Relationship::fromId)
                        .thenComparing(Relationship::toId)
                        .thenComparing(r -> r.kind().name()))
                .toList();
    }

    private boolean isCallable(String id) {
        return model.entity(id).map(e -> switch (e.kind()) {
            case METHOD, FUNCTION, PROCEDURE, CONSTRUCTOR -> true;
            default -> false;
        }).orElse(false);
    }

    private String labelOf(String id) {
        return model.entity(id).map(e -> switch (e.kind()) {
            case ENDPOINT, DATABASE_OBJECT, DATA_SOURCE, DATA_SINK, PACKAGE -> e.name();
            default -> shortName(e.qualifiedName());
        }).orElse(id);
    }

    private static String shortName(String qn) {
        // Keep the declaring type + member for readability: com.x.A#m() -> A#m()
        int hash = qn.indexOf('#');
        String type = hash >= 0 ? qn.substring(0, hash) : qn;
        int dot = type.lastIndexOf('.');
        String shortType = dot >= 0 ? type.substring(dot + 1) : type;
        return hash >= 0 ? shortType + qn.substring(hash) : shortType;
    }

    private static GraphModel.Category riskCategory(ComponentDependency.Risk risk) {
        return switch (risk) {
            case HIGH -> GraphModel.Category.RISK_HIGH;
            case MEDIUM -> GraphModel.Category.RISK_MEDIUM;
            case LOW -> GraphModel.Category.RISK_LOW;
        };
    }

    private static String architectureRole(Entity e) {
        String role = e.attribute(Entity.Attributes.ROLE).orElse(null);
        if (role != null && ROLE_LAYER.containsKey(role)) {
            return role;
        }
        return switch (e.kind()) {
            case ENDPOINT -> "ENDPOINT";
            case DATABASE_OBJECT -> "TABLE";
            case DATA_SOURCE -> "SOURCE";
            case DATA_SINK -> "SINK";
            case VARIABLE -> "ada".equals(e.language()) ? "STATE" : null;
            default -> null;
        };
    }

    private static GraphModel.Category architectureCategory(Entity e) {
        String role = e.attribute(Entity.Attributes.ROLE).orElse("");
        return switch (role) {
            case "controller" -> GraphModel.Category.CONTROLLER;
            case "service" -> GraphModel.Category.SERVICE;
            case "mapper-interface", "validator" -> GraphModel.Category.MAPPER;
            case "repository" -> GraphModel.Category.REPOSITORY;
            default -> switch (e.kind()) {
                case ENDPOINT -> GraphModel.Category.ENDPOINT;
                case DATABASE_OBJECT -> GraphModel.Category.TABLE;
                case DATA_SOURCE -> GraphModel.Category.SOURCE;
                case DATA_SINK -> GraphModel.Category.SINK;
                case VARIABLE -> GraphModel.Category.STATE;
                default -> GraphModel.Category.DEFAULT;
            };
        };
    }
}
