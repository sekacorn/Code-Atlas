package com.codeatlas.agents;

import com.codeatlas.reporting.Json;

import java.util.List;
import java.util.StringJoiner;

/**
 * A deterministic agent's full report: a titled, fixed-order list of answers
 * pinned to one scan. Text for humans, deterministic JSON for machines.
 */
public record AgentReport(String scanId, String title, List<AgentAnswer> answers) {

    public AgentReport {
        answers = List.copyOf(answers);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        String bar = "=".repeat(72);
        sb.append(bar).append('\n');
        sb.append("  ").append(title).append(" - scan ").append(scanId).append('\n');
        sb.append(bar).append('\n');
        for (AgentAnswer a : answers) {
            sb.append('\n').append(a.toText());
            sb.append("-".repeat(72)).append('\n');
        }
        return sb.toString();
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"scanId\": ").append(Json.quote(scanId))
          .append(",\n  \"title\": ").append(Json.quote(title))
          .append(",\n  \"answers\": [\n");
        StringJoiner arr = new StringJoiner(",\n");
        for (AgentAnswer a : answers) {
            arr.add("    {\"question\": " + Json.quote(a.question())
                    + ",\n     \"answer\": " + Json.quote(a.answer())
                    + ",\n     \"confirmedFacts\": " + strings(a.confirmedFacts())
                    + ",\n     \"inferredFindings\": " + strings(a.inferredFindings())
                    + ",\n     \"evidence\": " + citations(a.evidence())
                    + ",\n     \"confidence\": " + Json.quote(a.confidence())
                    + ",\n     \"unresolvedQuestions\": " + strings(a.unresolvedQuestions())
                    + ",\n     \"knownLimitations\": " + strings(a.knownLimitations())
                    + ",\n     \"suggestedNextInvestigation\": " + strings(a.suggestedNextInvestigation())
                    + "}");
        }
        sb.append(arr).append("\n  ]\n}\n");
        return sb.toString();
    }

    private static String strings(List<String> list) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        list.forEach(s -> j.add(Json.quote(s)));
        return j.toString();
    }

    private static String citations(List<AgentAnswer.Citation> list) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringJoiner j = new StringJoiner(", ", "[", "]");
        list.forEach(c -> j.add("{\"stableId\": " + Json.quote(c.stableId())
                + ", \"location\": " + Json.quote(c.location()) + "}"));
        return j.toString();
    }
}
