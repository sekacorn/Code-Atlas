package com.codeatlas.reporting;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.ComponentDependency;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.RepositoryMetrics;
import com.codeatlas.analysis.lineage.LineageSummary;

import java.util.Locale;

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
        sb.append("  \"lineage\": ").append(lineage(data.analysis().lineage())).append(",\n");
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

    private String lineage(LineageSummary s) {
        StringBuilder sb = new StringBuilder("{\n");
        StringJoiner endpoints = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (LineageSummary.EndpointView e : s.endpoints()) {
            endpoints.add("{\"stableId\": " + Json.quote(e.stableId())
                    + ", \"method\": " + Json.quote(e.httpMethod())
                    + ", \"path\": " + Json.quote(e.path())
                    + ", \"handler\": " + Json.quote(e.handler())
                    + ", \"pathUnresolved\": " + e.pathUnresolved()
                    + ", \"validated\": " + e.validated() + "}");
        }
        sb.append("    \"endpoints\": ").append(s.endpoints().isEmpty() ? "[]" : endpoints).append(",\n");

        StringJoiner stores = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (LineageSummary.StoreView v : s.stores()) {
            stores.add("{\"stableId\": " + Json.quote(v.stableId())
                    + ", \"name\": " + Json.quote(v.name())
                    + ", \"mappedFrom\": " + Json.quote(v.mappedFromEntity())
                    + ", \"nameInferred\": " + v.nameInferred() + "}");
        }
        sb.append("    \"dataStores\": ").append(s.stores().isEmpty() ? "[]" : stores).append(",\n");

        StringJoiner sources = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (LineageSummary.IoView v : s.sources()) {
            sources.add("{\"stableId\": " + Json.quote(v.stableId())
                    + ", \"name\": " + Json.quote(v.name())
                    + ", \"direction\": " + Json.quote(v.direction())
                    + ", \"description\": " + Json.quote(v.description()) + "}");
        }
        sb.append("    \"inputSources\": ").append(s.sources().isEmpty() ? "[]" : sources).append(",\n");

        StringJoiner traces = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (LineageSummary.EndpointTrace t : s.traces()) {
            traces.add(traceJson(t, "endpoint"));
        }
        sb.append("    \"traces\": ").append(s.traces().isEmpty() ? "[]" : traces).append(",\n");

        StringJoiner sourceTraces = new StringJoiner(",\n      ", "[\n      ", "\n    ]");
        for (LineageSummary.EndpointTrace t : s.sourceTraces()) {
            sourceTraces.add(traceJson(t, "source"));
        }
        sb.append("    \"sourceTraces\": ").append(s.sourceTraces().isEmpty() ? "[]" : sourceTraces).append(",\n");

        var c = s.coverage();
        sb.append("    \"coverage\": {")
          .append("\"endpointsDetected\": ").append(c.endpointsDetected())
          .append(", \"endpointsWithStorePath\": ").append(c.endpointsWithStorePath())
          .append(", \"repositoriesDetected\": ").append(c.repositoriesDetected())
          .append(", \"repositoriesMappedToEntities\": ").append(c.repositoriesMappedToEntities())
          .append(", \"entitiesMappedToTables\": ").append(c.entitiesMappedToTables())
          .append(", \"resolvedEdges\": ").append(c.resolvedEdges())
          .append(", \"inferredEdges\": ").append(c.inferredEdges())
          .append(", \"unresolvedEdges\": ").append(c.unresolvedEdges())
          .append(", \"completePathsWithinEvidence\": ").append(c.completePaths())
          .append(", \"partialPaths\": ").append(c.partialPaths())
          .append("}\n  }");
        return sb.toString();
    }

    private String traceJson(LineageSummary.EndpointTrace t, String rootKey) {
        StringJoiner steps = new StringJoiner(", ", "[", "]");
        t.steps().forEach(x -> steps.add(Json.quote(x)));
        return "{\"" + rootKey + "\": " + Json.quote(t.endpointId())
                + ", \"reachesStore\": " + t.reachesStore()
                + ", \"gapCount\": " + t.gapCount()
                + ", \"confidence\": " + String.format(Locale.ROOT, "%.2f", t.minConfidence())
                + ", \"steps\": " + steps + "}";
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
