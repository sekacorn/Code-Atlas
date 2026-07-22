package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 10 output: one step in the suggested reading order for a new developer.
 * The order is a deterministic template adapted to the evidence actually present
 * (build files first, then entry points, interfaces, the boundary, core Ada
 * packages, persistence, output, tests). Every step says why it matters.
 *
 * @param order            1-based position in the reading order
 * @param targetId         file or entity stable id to read
 * @param displayName      readable name of the file/entity
 * @param reason           why it matters
 * @param questionAnswered the question reading it answers
 * @param prerequisites    what to read first (may be empty)
 * @param evidence         supporting references
 * @param confidence       a confidence band
 */
public record ReadingRecommendation(int order,
                                    String targetId,
                                    String displayName,
                                    String reason,
                                    String questionAnswered,
                                    List<String> prerequisites,
                                    List<EvidenceRef> evidence,
                                    String confidence) {
    public ReadingRecommendation {
        prerequisites = List.copyOf(prerequisites);
        evidence = List.copyOf(evidence);
    }
}
