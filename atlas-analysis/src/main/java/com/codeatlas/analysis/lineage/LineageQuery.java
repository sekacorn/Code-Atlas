package com.codeatlas.analysis.lineage;

/**
 * A lineage traversal request.
 *
 * @param startId         stable id of the entity to trace from
 * @param direction       which way data flow is followed
 * @param maxDepth        maximum number of edges along one path
 * @param includeInferred whether INFERRED edges may appear in paths
 * @param minConfidence   edges below this confidence are excluded (default 0.40 —
 *                        the documented "do not include by default" floor)
 */
public record LineageQuery(String startId,
                           Direction direction,
                           int maxDepth,
                           boolean includeInferred,
                           double minConfidence) {

    public enum Direction {
        UPSTREAM,
        DOWNSTREAM,
        BOTH
    }

    public static LineageQuery downstream(String startId) {
        return new LineageQuery(startId, Direction.DOWNSTREAM, 8, true, 0.40);
    }

    public static LineageQuery upstream(String startId) {
        return new LineageQuery(startId, Direction.UPSTREAM, 8, true, 0.40);
    }
}
