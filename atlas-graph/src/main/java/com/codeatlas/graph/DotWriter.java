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
            Palette.Emphasis emphasis = Palette.emphasis(n.category());
            sb.append("  ").append(id(n.id()))
              .append(" [label=").append(quote(n.label()))
              .append(", fillcolor=\"").append(Palette.fill(n.category())).append("\"")
              .append(", color=\"").append(Palette.border(n.category())).append("\"")
              .append(", style=\"").append(style(emphasis)).append("\"")
              .append(", penwidth=").append(emphasis == Palette.Emphasis.NORMAL ? "1" : "2")
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

    /**
     * The base node style plus {@link Palette}'s non-colour channel. Graphviz takes
     * these as a style list, so the dash or the heavier pen rides along with the
     * rounded, filled box rather than replacing it.
     */
    private static String style(Palette.Emphasis emphasis) {
        return switch (emphasis) {
            case NORMAL -> "rounded,filled";
            case DASHED -> "rounded,filled,dashed";
            case HEAVY -> "rounded,filled,bold";
        };
    }

    private static String id(String stableId) {
        return quote(stableId);
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    /** Kept for symmetry with SVG sizing; DOT does its own layout. */
    static String num(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
