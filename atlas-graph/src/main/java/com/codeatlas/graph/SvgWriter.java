package com.codeatlas.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders a {@link GraphModel} as a self-contained SVG using a deterministic
 * layered layout: nodes are placed in columns by layer (left→right) and rows by
 * sorted order within a layer; edges are drawn as arrows. No external assets, no
 * scripts — the SVG opens offline in any browser.
 *
 * <p>The layout is simple by design (Code Atlas is not a graph-layout engine); for
 * large or dense graphs, {@code --format dot} piped to Graphviz gives better
 * routing. Output is byte-deterministic for identical input.
 */
public final class SvgWriter {

    private static final int NODE_W = 190;
    private static final int NODE_H = 34;
    private static final int H_GAP = 70;
    private static final int V_GAP = 16;
    private static final int MARGIN = 24;
    private static final int TITLE_H = 44;

    public String render(GraphModel graph) {
        // Group nodes by layer, ordered; assign row within layer.
        TreeMap<Integer, List<GraphModel.Node>> byLayer = new TreeMap<>();
        for (GraphModel.Node n : graph.nodes()) {
            byLayer.computeIfAbsent(n.layer(), k -> new ArrayList<>()).add(n);
        }
        byLayer.values().forEach(l -> l.sort(Comparator.comparing(GraphModel.Node::label)
                .thenComparing(GraphModel.Node::id)));

        Map<String, double[]> pos = new HashMap<>(); // id -> {x, y}
        int maxRows = 0;
        int layerIndex = 0;
        List<Integer> layerKeys = new ArrayList<>(byLayer.keySet());
        for (int li = 0; li < layerKeys.size(); li++) {
            List<GraphModel.Node> layerNodes = byLayer.get(layerKeys.get(li));
            for (int row = 0; row < layerNodes.size(); row++) {
                double x = MARGIN + li * (NODE_W + H_GAP);
                double y = TITLE_H + MARGIN + row * (NODE_H + V_GAP);
                pos.put(layerNodes.get(row).id(), new double[]{x, y});
            }
            maxRows = Math.max(maxRows, layerNodes.size());
            layerIndex = li;
        }
        double width = MARGIN * 2 + (layerIndex + 1) * NODE_W + layerIndex * H_GAP;
        double height = TITLE_H + MARGIN * 2 + Math.max(1, maxRows) * (NODE_H + V_GAP);

        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(num(width))
          .append("\" height=\"").append(num(height)).append("\" viewBox=\"0 0 ")
          .append(num(width)).append(' ').append(num(height)).append("\" font-family=\"sans-serif\">\n");
        sb.append("  <defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"3\" "
                + "orient=\"auto\"><path d=\"M0,0 L7,3 L0,6 Z\" fill=\"#888\"/></marker></defs>\n");
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        sb.append("  <text x=\"").append(MARGIN).append("\" y=\"26\" font-size=\"16\" font-weight=\"600\" "
                + "fill=\"#222\">").append(esc(graph.title())).append("</text>\n");
        if (!graph.note().isEmpty()) {
            sb.append("  <text x=\"").append(MARGIN).append("\" y=\"40\" font-size=\"11\" fill=\"#b45309\">")
              .append(esc(graph.note())).append("</text>\n");
        }
        if (graph.nodes().isEmpty()) {
            sb.append("  <text x=\"").append(MARGIN).append("\" y=\"70\" font-size=\"13\" fill=\"#666\">"
                    + "No nodes for this graph in the current scan.</text>\n</svg>\n");
            return sb.toString();
        }

        // Edges first (under nodes).
        for (GraphModel.Edge e : graph.edges()) {
            double[] a = pos.get(e.from());
            double[] b = pos.get(e.to());
            if (a == null || b == null) {
                continue;
            }
            double x1 = a[0] + NODE_W;
            double y1 = a[1] + NODE_H / 2.0;
            double x2 = b[0];
            double y2 = b[1] + NODE_H / 2.0;
            boolean forward = b[0] > a[0];
            if (!forward) {
                // back/same-layer edge: exit left, re-enter left, with a curve.
                x1 = a[0];
                x2 = b[0];
            }
            double mx = (x1 + x2) / 2.0;
            sb.append("  <path d=\"M").append(num(x1)).append(',').append(num(y1))
              .append(" C").append(num(mx)).append(',').append(num(y1)).append(' ')
              .append(num(mx)).append(',').append(num(y2)).append(' ')
              .append(num(x2)).append(',').append(num(y2))
              .append("\" fill=\"none\" stroke=\"#bbbbbb\" stroke-width=\"1\" marker-end=\"url(#arrow)\"/>\n");
        }

        // Nodes.
        for (GraphModel.Node n : graph.nodes()) {
            double[] p = pos.get(n.id());
            if (p == null) {
                continue;
            }
            sb.append("  <g>\n");
            sb.append("    <rect x=\"").append(num(p[0])).append("\" y=\"").append(num(p[1]))
              .append("\" width=\"").append(NODE_W).append("\" height=\"").append(NODE_H)
              .append("\" rx=\"6\" fill=\"").append(DotWriter.fill(n.category()))
              .append("\" stroke=\"").append(DotWriter.border(n.category()))
              .append("\" stroke-width=\"").append(n.category() == GraphModel.Category.DEAD ? "2" : "1")
              .append(n.category() == GraphModel.Category.DEAD ? "\" stroke-dasharray=\"4,2" : "")
              .append("\"/>\n");
            sb.append("    <text x=\"").append(num(p[0] + NODE_W / 2.0)).append("\" y=\"")
              .append(num(p[1] + NODE_H / 2.0 + 4)).append("\" font-size=\"11\" text-anchor=\"middle\" "
                    + "fill=\"#1a1a1a\">").append(esc(truncate(n.label(), 26))).append("</text>\n");
            sb.append("  </g>\n");
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
