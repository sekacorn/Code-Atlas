package com.codeatlas.reporting;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * Serialises a lineage traversal to deterministic JSON: no timestamps, stable
 * ordering (inherited from the traversal), fixed number formatting. Two runs over
 * identical content produce byte-identical output.
 */
public final class LineageJsonWriter {

    public String render(String scanId, LineageQuery query, LineageResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"scanId\": ").append(Json.quote(scanId)).append(",\n");
        sb.append("  \"query\": {")
          .append("\"start\": ").append(Json.quote(query.startId()))
          .append(", \"direction\": ").append(Json.quote(query.direction().name()))
          .append(", \"maxDepth\": ").append(query.maxDepth())
          .append(", \"includeInferred\": ").append(query.includeInferred())
          .append(", \"minConfidence\": ").append(fmt(query.minConfidence()))
          .append("},\n");

        StringJoiner nodes = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        for (LineageResult.Node n : result.nodes()) {
            nodes.add("{\"id\": " + Json.quote(n.id())
                    + ", \"kind\": " + Json.quote(n.kind())
                    + ", \"label\": " + Json.quote(n.label())
                    + ", \"location\": " + Json.quote(n.location()) + "}");
        }
        sb.append("  \"nodes\": ").append(result.nodes().isEmpty() ? "[]" : nodes).append(",\n");

        StringJoiner paths = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        for (LineageResult.Path p : result.paths()) {
            StringJoiner nodeIds = new StringJoiner(", ", "[", "]");
            p.nodeIds().forEach(id -> nodeIds.add(Json.quote(id)));
            StringJoiner edges = new StringJoiner(", ", "[", "]");
            for (LineageResult.Edge e : p.edges()) {
                edges.add("{\"from\": " + Json.quote(e.fromId())
                        + ", \"to\": " + Json.quote(e.toId())
                        + ", \"kind\": " + Json.quote(e.kind())
                        + ", \"ruleId\": " + Json.quote(e.ruleId())
                        + ", \"confidence\": " + fmt(e.confidence())
                        + ", \"status\": " + Json.quote(e.status())
                        + ", \"inferred\": " + e.inferred()
                        + ", \"ambiguous\": " + e.ambiguous()
                        + ", \"evidence\": " + Json.quote(e.location()) + "}");
            }
            paths.add("{\"nodes\": " + nodeIds + ", \"edges\": " + edges
                    + ", \"confidence\": " + fmt(p.minConfidence()) + "}");
        }
        sb.append("  \"paths\": ").append(result.paths().isEmpty() ? "[]" : paths).append(",\n");

        StringJoiner gaps = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        for (LineageResult.Gap g : result.gaps()) {
            gaps.add("{\"at\": " + Json.quote(g.atId())
                    + ", \"kind\": " + Json.quote(g.kind())
                    + ", \"description\": " + Json.quote(g.description()) + "}");
        }
        sb.append("  \"unresolvedGaps\": ").append(result.gaps().isEmpty() ? "[]" : gaps).append(",\n");
        sb.append("  \"cyclesDetected\": ").append(result.cyclesDetected()).append(",\n");
        sb.append("  \"truncated\": ").append(result.truncated()).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
