package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 12 output: the compact final summary. It answers the onboarding questions
 * in order, and — crucially — keeps confirmed facts, resolved relationships,
 * inferred architecture, unresolved questions and known limitations in separate
 * buckets so a reader never mistakes an inference for a fact.
 *
 * @param answers               ordered question→answer pairs
 * @param confirmedFacts        things established directly from evidence
 * @param resolvedRelationships resolved edges worth highlighting
 * @param inferredArchitecture  architecture statements that are inferences
 * @param unresolvedQuestions   the open questions onboarding could not answer
 * @param knownLimitations      the standing analysis limitations that apply
 */
public record FinalSummary(List<QA> answers,
                           List<String> confirmedFacts,
                           List<String> resolvedRelationships,
                           List<String> inferredArchitecture,
                           List<String> unresolvedQuestions,
                           List<String> knownLimitations) {

    public FinalSummary {
        answers = List.copyOf(answers);
        confirmedFacts = List.copyOf(confirmedFacts);
        resolvedRelationships = List.copyOf(resolvedRelationships);
        inferredArchitecture = List.copyOf(inferredArchitecture);
        unresolvedQuestions = List.copyOf(unresolvedQuestions);
        knownLimitations = List.copyOf(knownLimitations);
    }

    /** One question and its compact answer. */
    public record QA(String question, String answer) {
    }
}
