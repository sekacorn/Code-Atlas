package com.codeatlas.agents;

import java.util.List;

/**
 * The required structure for every agent answer: the answer itself, confirmed
 * facts strictly separated from inferred findings, cited evidence, an explicit
 * confidence statement, open questions, known limitations and a concrete next
 * investigation. Deterministic agents fill this from tool-API results only —
 * nothing here is ever invented.
 */
public record AgentAnswer(String question,
                          String answer,
                          List<String> confirmedFacts,
                          List<String> inferredFindings,
                          List<Citation> evidence,
                          String confidence,
                          List<String> unresolvedQuestions,
                          List<String> knownLimitations,
                          List<String> suggestedNextInvestigation) {

    /** A pointer to the graph evidence behind a statement. */
    public record Citation(String stableId, String location) {
        public String render() {
            return location == null || location.isBlank() ? stableId : stableId + " (" + location + ")";
        }
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Question: ").append(question).append('\n');
        sb.append("Answer: ").append(answer).append('\n');
        section(sb, "Confirmed facts", confirmedFacts);
        section(sb, "Inferred findings", inferredFindings);
        if (!evidence.isEmpty()) {
            sb.append("Evidence:\n");
            evidence.forEach(c -> sb.append("  - ").append(c.render()).append('\n'));
        }
        sb.append("Confidence: ").append(confidence).append('\n');
        section(sb, "Unresolved questions", unresolvedQuestions);
        section(sb, "Known limitations", knownLimitations);
        section(sb, "Suggested next investigation", suggestedNextInvestigation);
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        lines.forEach(l -> sb.append("  - ").append(l).append('\n'));
    }
}
