package com.codeatlas.analysis.lineage;

import java.util.List;

/**
 * The outcome of one lineage traversal. Nodes, edges and paths use stable ids
 * throughout; unresolved gaps are first-class data, and a result never implies
 * completeness beyond "no unresolved edge found within the analyzed evidence".
 *
 * @param startId        the traced entity's stable id
 * @param direction      the traversal direction
 * @param nodes          every node touched, sorted by id
 * @param paths          ordered root-to-terminal paths, deterministic order
 * @param gaps           unresolved or external segments discovered along the way
 * @param cyclesDetected whether any cycle was cut during traversal
 * @param truncated      whether depth or path limits cut the traversal short
 */
public record LineageResult(String startId,
                            LineageQuery.Direction direction,
                            List<Node> nodes,
                            List<Path> paths,
                            List<Gap> gaps,
                            boolean cyclesDetected,
                            boolean truncated) {

    public LineageResult {
        nodes = List.copyOf(nodes);
        paths = List.copyOf(paths);
        gaps = List.copyOf(gaps);
    }

    /** A node in a lineage result: stable id, entity kind, label and evidence location. */
    public record Node(String id, String kind, String label, String location) {
    }

    /** A traversed edge with its evidence metadata. */
    public record Edge(String fromId, String toId, String kind, String ruleId,
                       double confidence, String status, boolean inferred, boolean ambiguous,
                       String location) {
    }

    /** One ordered path; {@code minConfidence} is the weakest edge along it. */
    public record Path(List<String> nodeIds, List<Edge> edges, double minConfidence) {
        public Path {
            nodeIds = List.copyOf(nodeIds);
            edges = List.copyOf(edges);
        }
    }

    /** An honest hole in the lineage: something detected but not fully connected. */
    public record Gap(String atId, String kind, String description) {
        public static final String UNRESOLVED_TARGET = "UNRESOLVED_TARGET";
        public static final String EXTERNAL_CONSUMER = "EXTERNAL_CONSUMER";
        public static final String AMBIGUOUS_IMPLEMENTATION = "AMBIGUOUS_IMPLEMENTATION";
    }
}
