package com.codeatlas.reporting;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.ComponentDependency;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.RepositoryMetrics;

import java.util.StringJoiner;

/**
 * Serialises the analysis to JSON for machine consumption (CI gates, dashboards,
 * or the optional AI layer, which receives only this structured context).
 */
public final class JsonReporter {

    public String render(ReportData data) {
        RepositoryMetrics m = data.analysis().metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"repository\": ").append(Json.quote(data.repositoryName())).append(",\n");
        sb.append("  \"generatedAt\": ").append(Json.quote(data.generatedAt().toString())).append(",\n");
        sb.append("  \"scanId\": ").append(Json.quote(data.scanId())).append(",\n");
        sb.append("  \"scanStatus\": ").append(Json.quote(data.coverage().isPartial() ? "PARTIAL" : "COMPLETE")).append(",\n");
        sb.append("  \"coverage\": ").append(coverage(data.coverage())).append(",\n");

        sb.append("  \"metrics\": {\n");
        sb.append("    \"totalFiles\": ").append(m.totalFiles()).append(",\n");
        sb.append("    \"totalLines\": ").append(m.totalLines()).append(",\n");
        sb.append("    \"codeLines\": ").append(m.codeLines()).append(",\n");
        sb.append("    \"commentLines\": ").append(m.commentLines()).append(",\n");
        sb.append("    \"blankLines\": ").append(m.blankLines()).append(",\n");
        sb.append("    \"filesByLanguage\": ").append(mapToJson(m.filesByLanguage())).append(",\n");
        sb.append("    \"entityCounts\": ").append(entityCounts(m)).append("\n");
        sb.append("  },\n");

        sb.append("  \"complexityHotspots\": ").append(complexity(data)).append(",\n");
        sb.append("  \"deadCode\": ").append(deadCode(data)).append(",\n");
        sb.append("  \"dependencies\": ").append(dependencies(data)).append(",\n");
        sb.append("  \"diagnostics\": ").append(diagnostics(data)).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String coverage(AnalysisCoverage c) {
        return "{"
                + "\"filesDiscovered\": " + c.filesDiscovered()
                + ", \"filesAnalyzed\": " + c.filesAnalyzed()
                + ", \"filesSkipped\": " + c.filesSkipped()
                + ", \"filesFailed\": " + c.filesFailed()
                + ", \"filesUnreadable\": " + c.filesUnreadable()
                + ", \"filesReused\": " + c.filesReused()
                + ", \"unsupportedFileTypes\": " + c.unsupportedFileTypes()
                + ", \"referencesResolved\": " + c.referencesResolved()
                + ", \"referencesUnresolved\": " + c.referencesUnresolved()
                + ", \"referencesAmbiguous\": " + c.referencesAmbiguous()
                + ", \"resolutionRatePercent\": " + c.resolutionRatePercent()
                + ", \"partial\": " + c.isPartial()
                + "}";
    }

    private String mapToJson(java.util.Map<String, Integer> map) {
        StringJoiner j = new StringJoiner(", ", "{", "}");
        map.forEach((k, v) -> j.add(Json.quote(k) + ": " + v));
        return j.toString();
    }

    private String entityCounts(RepositoryMetrics m) {
        StringJoiner j = new StringJoiner(", ", "{", "}");
        m.entityCounts().forEach((k, v) -> j.add(Json.quote(k.name()) + ": " + v));
        return j.toString();
    }

    private String complexity(ReportData data) {
        StringJoiner arr = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        if (data.analysis().complexityHotspots().isEmpty()) {
            return "[]";
        }
        for (ComplexityHotspot h : data.analysis().complexityHotspots()) {
            arr.add("{\"stableId\": " + Json.quote(h.stableId())
                    + ", \"name\": " + Json.quote(h.qualifiedName())
                    + ", \"complexity\": " + h.complexity()
                    + ", \"risk\": " + Json.quote(h.risk().name())
                    + ", \"location\": " + Json.quote(h.location().toString()) + "}");
        }
        return arr.toString();
    }

    private String deadCode(ReportData data) {
        if (data.analysis().deadCode().isEmpty()) {
            return "[]";
        }
        StringJoiner arr = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        for (DeadCodeCandidate c : data.analysis().deadCode()) {
            StringJoiner ev = new StringJoiner(", ", "[", "]");
            c.evidence().forEach(e -> ev.add(Json.quote(e)));
            arr.add("{\"stableId\": " + Json.quote(c.stableId())
                    + ", \"name\": " + Json.quote(c.qualifiedName())
                    + ", \"kind\": " + Json.quote(c.kind().name())
                    + ", \"confidence\": " + c.confidence()
                    + ", \"recommendation\": " + Json.quote(c.recommendation())
                    + ", \"evidence\": " + ev
                    + ", \"location\": " + Json.quote(c.location().toString()) + "}");
        }
        return arr.toString();
    }

    private String diagnostics(ReportData data) {
        var diags = data.model().diagnostics();
        if (diags.isEmpty()) {
            return "[]";
        }
        StringJoiner arr = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        for (var d : diags) {
            arr.add("{\"code\": " + Json.quote(d.code()) + ", \"message\": " + Json.quote(d.message()) + "}");
        }
        return arr.toString();
    }

    private String dependencies(ReportData data) {
        StringJoiner arr = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
        if (data.analysis().dependencies().components().isEmpty()) {
            return "[]";
        }
        for (ComponentDependency c : data.analysis().dependencies().components()) {
            arr.add("{\"name\": " + Json.quote(c.name())
                    + ", \"dependencies\": " + c.dependencies()
                    + ", \"dependents\": " + c.dependents()
                    + ", \"risk\": " + Json.quote(c.risk().name()) + "}");
        }
        return arr.toString();
    }
}
