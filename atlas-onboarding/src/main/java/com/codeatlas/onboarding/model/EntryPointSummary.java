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
 * @param category         what kind of entry point this is — the machine-readable
 *                         classification other stages branch on ({@code type} is a
 *                         display label and must never be matched against)
 * @param type             human-readable type (e.g. "Java main method", "REST endpoint",
 *                         "Ada main procedure (inferred)", "Build-declared main (gnat)")
 * @param location         source {@code file:line}
 * @param evidence         supporting references
 * @param confidence       a confidence band with its basis
 * @param resolutionStatus DISCOVERED / INFERRED (naming/structure) etc.
 */
public record EntryPointSummary(String stableId,
                                String displayName,
                                String language,
                                EntryPointCategory category,
                                String type,
                                String location,
                                List<EvidenceRef> evidence,
                                String confidence,
                                String resolutionStatus) {

    /** True for executable main units, however they were discovered. */
    public boolean isMain() {
        return category == EntryPointCategory.MAIN;
    }
}
