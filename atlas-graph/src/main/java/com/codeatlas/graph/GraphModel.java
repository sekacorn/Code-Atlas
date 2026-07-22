package com.codeatlas.graph;

import java.util.List;

/**
 * A laid-out, renderer-agnostic graph: nodes (with a category and a layer index)
 * and directed edges. {@link GraphBuilder} produces it deterministically from the
 * software model; {@link DotWriter} and {@link SvgWriter} render it.
 *
 * @param title     human-readable graph title
 * @param nodes     graph nodes, in deterministic order
 * @param edges     directed edges referencing node ids
 * @param truncated whether a size cap dropped part of the graph
 * @param note      caveat shown to the reader (e.g. truncation, emptiness)
 */
public record GraphModel(String title, List<Node> nodes, List<Edge> edges,
                         boolean truncated, String note) {

    public GraphModel {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** A node's visual category, mapped to colour by the renderers. */
    public enum Category {
        DEFAULT, RISK_HIGH, RISK_MEDIUM, RISK_LOW,
        DEAD, ACTIVE,
        ENDPOINT, CONTROLLER, SERVICE, MAPPER, REPOSITORY, TABLE, SOURCE, SINK, STATE
    }

    /**
     * @param id     stable id (used as the graph key and in DOT)
     * @param label  short display label
     * @param category visual category
     * @param layer  0-based layer index for layered layout (left→right or top-down)
     */
    public record Node(String id, String label, Category category, int layer) {
    }

    public record Edge(String from, String to, String label) {
    }
}
