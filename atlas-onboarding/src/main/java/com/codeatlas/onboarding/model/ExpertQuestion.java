package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 11 output: one evidence-backed question a new developer can take to a
 * specific kind of subject-matter expert. Questions are grounded in real
 * components and real evidence, and are only generated for roles the repository's
 * evidence actually supports (no Ada-specialist question when there is no Ada, no
 * question about a component that does not exist).
 *
 * @param role         the expert to ask (e.g. "Ada specialist", "Database engineer")
 * @param question     the specific, grounded question
 * @param componentIds the real stable ids the question references
 * @param evidence     the evidence that motivates it
 * @param basis        "confirmed" (asks to confirm a resolved fact's runtime role)
 *                     or "unresolved" (asks to fill a specific analysis gap)
 */
public record ExpertQuestion(String role,
                             String question,
                             List<String> componentIds,
                             List<EvidenceRef> evidence,
                             String basis) {

    public static final String BASIS_CONFIRMED = "confirmed";
    public static final String BASIS_UNRESOLVED = "unresolved";
}
