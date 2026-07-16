package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 4 output: one probable application entry point, never classified on naming
 * alone — each is backed by a structural marker (a {@code main(String[])} signature,
 * an HTTP-verb mapping, a top-level library procedure) and cites its evidence.
 *
 * @param stableId         the entity's stable id
 * @param displayName      human name (qualified name or endpoint label)
 * @param language         "java" or "ada"
 * @param type             entry-point type (e.g. "Java main method", "REST endpoint",
 *                         "Ada main procedure", "Ada task")
 * @param location         source {@code file:line}
 * @param evidence         supporting references
 * @param confidence       a confidence band with its basis
 * @param resolutionStatus DISCOVERED / INFERRED (naming/structure) etc.
 */
public record EntryPointSummary(String stableId,
                                String displayName,
                                String language,
                                String type,
                                String location,
                                List<EvidenceRef> evidence,
                                String confidence,
                                String resolutionStatus) {
}
