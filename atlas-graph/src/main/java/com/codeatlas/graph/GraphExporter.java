package com.codeatlas.graph;

import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.model.SoftwareModel;

/**
 * Facade: builds and renders a graph in one call. Deterministic and offline.
 */
public final class GraphExporter {

    public enum Format {
        DOT, SVG;

        public static Format from(String s) {
            return switch (s.toLowerCase()) {
                case "dot", "gv" -> DOT;
                case "svg" -> SVG;
                default -> throw new IllegalArgumentException("Unknown format '" + s + "'. Use: dot, svg.");
            };
        }

        public String extension() {
            return this == DOT ? "dot" : "svg";
        }
    }

    private final DotWriter dot = new DotWriter();
    private final SvgWriter svg = new SvgWriter();

    /** Renders {@code type} in {@code format}, computing analysis from the model. */
    public String export(SoftwareModel model, GraphType type, Format format) {
        return export(model, new AnalysisEngine().analyze(model), type, format);
    }

    public String export(SoftwareModel model, AnalysisResult analysis, GraphType type, Format format) {
        GraphModel graph = new GraphBuilder(model, analysis).build(type);
        return format == Format.DOT ? dot.render(graph) : svg.render(graph);
    }
}
