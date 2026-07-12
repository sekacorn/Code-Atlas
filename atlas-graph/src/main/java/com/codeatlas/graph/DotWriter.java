package com.codeatlas.graph;

import java.util.Locale;

/**
 * Renders a {@link GraphModel} as Graphviz DOT. DOT is the recommended format for
 * large graphs (pipe to {@code dot -Tsvg}); Code Atlas emits it deterministically
 * so diffs are meaningful.
 */
public final class DotWriter {

    public String render(GraphModel graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph CodeAtlas {\n");
        sb.append("  graph [rankdir=LR, label=").append(quote(graph.title()
                + (graph.note().isEmpty() ? "" : "\\n" + graph.note())))
          .append(", labelloc=t, fontname=\"sans-serif\"];\n");
        sb.append("  node [shape=box, style=\"rounded,filled\", fontname=\"sans-serif\", fontsize=10];\n");
        sb.append("  edge [color=\"#888888\"];\n");
        for (GraphModel.Node n : graph.nodes()) {
            sb.append("  ").append(id(n.id()))
              .append(" [label=").append(quote(n.label()))
              .append(", fillcolor=\"").append(fill(n.category())).append("\"")
              .append(", color=\"").append(border(n.category())).append("\"")
              .append("];\n");
        }
        for (GraphModel.Edge e : graph.edges()) {
            sb.append("  ").append(id(e.from())).append(" -> ").append(id(e.to()));
            if (!e.label().isEmpty()) {
                sb.append(" [label=").append(quote(e.label())).append("]");
            }
            sb.append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String fill(GraphModel.Category c) {
        return switch (c) {
            case RISK_HIGH, DEAD -> "#f4c7c3";
            case RISK_MEDIUM -> "#fce8b2";
            case RISK_LOW, ACTIVE -> "#d9ead3";
            case ENDPOINT, SOURCE -> "#c9daf8";
            case CONTROLLER -> "#d0e0e3";
            case SERVICE -> "#d9d2e9";
            case MAPPER -> "#fff2cc";
            case REPOSITORY -> "#d9ead3";
            case TABLE, SINK -> "#ead1dc";
            case STATE -> "#fce5cd";
            default -> "#eeeeee";
        };
    }

    static String border(GraphModel.Category c) {
        return switch (c) {
            case RISK_HIGH, DEAD -> "#cc0000";
            case RISK_MEDIUM -> "#bf9000";
            default -> "#666666";
        };
    }

    private static String id(String stableId) {
        return "\"" + stableId.replace("\"", "\\\"") + "\"";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Kept for symmetry with SVG sizing; DOT does its own layout. */
    static String num(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
