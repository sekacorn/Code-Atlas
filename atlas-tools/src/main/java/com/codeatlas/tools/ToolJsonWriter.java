package com.codeatlas.tools;

import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.model.Diagnostic;
import com.codeatlas.reporting.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Deterministic JSON rendering for tool results — the wire format future agents
 * consume. No timestamps, stable ordering (inherited from the API), fixed
 * envelope: {@code operation, scanId, supported, truncated, totalMatches, note,
 * value}.
 */
public final class ToolJsonWriter {

    public String render(String operation, ToolResult<?> result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"operation\": ").append(Json.quote(operation)).append(",\n");
        sb.append("  \"scanId\": ").append(Json.quote(result.scanId())).append(",\n");
        sb.append("  \"supported\": ").append(result.supported()).append(",\n");
        sb.append("  \"truncated\": ").append(result.truncated()).append(",\n");
        sb.append("  \"totalMatches\": ").append(result.totalMatches()).append(",\n");
        sb.append("  \"note\": ").append(Json.quote(result.note())).append(",\n");
        sb.append("  \"value\": ").append(value(result.value())).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Optional<?> opt) {
            return opt.map(this::value).orElse("null");
        }
        if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringJoiner arr = new StringJoiner(",\n    ", "[\n    ", "\n  ]");
            list.forEach(item -> arr.add(value(item)));
            return arr.toString();
        }
        if (v instanceof Views.EntityView e) {
            return "{\"stableId\": " + Json.quote(e.stableId())
                    + ", \"kind\": " + Json.quote(e.kind())
                    + ", \"name\": " + Json.quote(e.name())
                    + ", \"qualifiedName\": " + Json.quote(e.qualifiedName())
                    + ", \"language\": " + Json.quote(e.language())
                    + ", \"location\": " + Json.quote(e.location())
                    + ", \"attributes\": " + stringMap(e.attributes()) + "}";
        }
        if (v instanceof Views.EdgeView e) {
            return "{\"from\": " + Json.quote(e.fromId())
                    + ", \"to\": " + Json.quote(e.toId())
                    + ", \"kind\": " + Json.quote(e.kind())
                    + ", \"ruleId\": " + Json.quote(e.ruleId())
                    + ", \"confidence\": " + Json.quote(e.confidence())
                    + ", \"status\": " + Json.quote(e.status())
                    + ", \"inferred\": " + e.inferred()
                    + ", \"ambiguous\": " + e.ambiguous()
                    + ", \"evidence\": " + Json.quote(e.evidence()) + "}";
        }
        if (v instanceof Views.NeighborView n) {
            return "{\"entity\": " + value(n.entity()) + ", \"edge\": " + value(n.edge()) + "}";
        }
        if (v instanceof Views.EvidenceView e) {
            return "{\"stableId\": " + Json.quote(e.stableId())
                    + ", \"location\": " + Json.quote(e.location())
                    + ", \"specLocation\": " + Json.quote(e.specLocation())
                    + ", \"bodyLocation\": " + Json.quote(e.bodyLocation())
                    + ", \"fileHash\": " + Json.quote(e.fileHash())
                    + ", \"note\": " + Json.quote(e.note()) + "}";
        }
        if (v instanceof Views.UnresolvedReference u) {
            return "{\"from\": " + Json.quote(u.fromId())
                    + ", \"target\": " + Json.quote(u.targetName())
                    + ", \"kind\": " + Json.quote(u.kind())
                    + ", \"location\": " + Json.quote(u.location()) + "}";
        }
        if (v instanceof Views.ImpactView i) {
            return "{\"target\": " + Json.quote(i.targetId())
                    + ",\n    \"directDependents\": " + value(i.directDependents())
                    + ",\n    \"indirectDependents\": " + value(i.indirectDependents())
                    + ",\n    \"databaseImpact\": " + value(i.databaseImpact())
                    + ",\n    \"downstreamLineage\": " + stringList(i.downstreamLineage())
                    + ",\n    \"unresolvedRisks\": " + stringList(i.unresolvedRisks())
                    + ",\n    \"limitations\": " + Json.quote(i.limitations()) + "}";
        }
        if (v instanceof Views.RepositorySummaryView s) {
            return "{\"totalFiles\": " + s.totalFiles()
                    + ", \"totalLines\": " + s.totalLines()
                    + ", \"codeLines\": " + s.codeLines()
                    + ", \"filesByLanguage\": " + intMap(s.filesByLanguage())
                    + ", \"entityCounts\": " + intMap(s.entityCounts())
                    + ", \"endpoints\": " + stringList(s.endpoints())
                    + ", \"dataStores\": " + stringList(s.dataStores())
                    + ", \"dataSources\": " + stringList(s.dataSources())
                    + ", \"dataSinks\": " + stringList(s.dataSinks())
                    + ", \"deadCodeCandidates\": " + s.deadCodeCandidates()
                    + ", \"complexityHotspots\": " + s.complexityHotspots()
                    + ", \"unresolvedReferences\": " + s.unresolvedReferences()
                    + ", \"diagnostics\": " + s.diagnostics() + "}";
        }
        if (v instanceof DeadCodeCandidate c) {
            StringJoiner ev = new StringJoiner(", ", "[", "]");
            c.evidence().forEach(x -> ev.add(Json.quote(x)));
            return "{\"stableId\": " + Json.quote(c.stableId())
                    + ", \"name\": " + Json.quote(c.qualifiedName())
                    + ", \"kind\": " + Json.quote(c.kind().name())
                    + ", \"confidence\": " + c.confidence()
                    + ", \"evidence\": " + ev
                    + ", \"location\": " + Json.quote(c.location().toString())
                    + ", \"recommendation\": " + Json.quote(c.recommendation()) + "}";
        }
        if (v instanceof ComplexityHotspot h) {
            return "{\"stableId\": " + Json.quote(h.stableId())
                    + ", \"name\": " + Json.quote(h.qualifiedName())
                    + ", \"complexity\": " + h.complexity()
                    + ", \"risk\": " + Json.quote(h.risk().name())
                    + ", \"location\": " + Json.quote(h.location().toString()) + "}";
        }
        if (v instanceof Diagnostic d) {
            return "{\"code\": " + Json.quote(d.code()) + ", \"message\": " + Json.quote(d.message()) + "}";
        }
        return Json.quote(String.valueOf(v));
    }

    private String stringMap(Map<String, String> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringJoiner j = new StringJoiner(", ", "{", "}");
        map.forEach((k, val) -> j.add(Json.quote(k) + ": " + Json.quote(val)));
        return j.toString();
    }

    private String intMap(Map<String, Integer> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringJoiner j = new StringJoiner(", ", "{", "}");
        map.forEach((k, val) -> j.add(Json.quote(k) + ": " + val));
        return j.toString();
    }

    private String stringList(List<String> list) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        list.forEach(s -> j.add(Json.quote(s)));
        return j.toString();
    }
}
