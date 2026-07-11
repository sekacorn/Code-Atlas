package com.codeatlas.analysis;

/**
 * An honest account of how much of the repository was actually understood.
 *
 * <p>A scan is rarely 100% complete on a real codebase: some files have no parser,
 * some fail, some references cannot be resolved. Surfacing these numbers &mdash;
 * and labelling a scan {@link #isPartial() partial} when they are non-zero &mdash;
 * is a core requirement: users must never mistake incomplete analysis for complete
 * coverage.
 *
 * @param filesDiscovered      files found by the scanner (after exclusions)
 * @param filesAnalyzed        files a parser understood without fatal errors
 * @param filesSkipped         files with no parser for their type
 * @param filesFailed          files a parser attempted but reported errors on
 * @param filesUnreadable      files that could not be read as text (e.g. binary)
 * @param unsupportedFileTypes distinct file extensions that had no parser
 * @param referencesResolved   cross-references linked to a concrete target
 * @param referencesUnresolved cross-references whose target was not found
 * @param referencesAmbiguous  cross-references with too many candidates to resolve
 */
public record AnalysisCoverage(int filesDiscovered,
                               int filesAnalyzed,
                               int filesSkipped,
                               int filesFailed,
                               int filesUnreadable,
                               int unsupportedFileTypes,
                               int referencesResolved,
                               int referencesUnresolved,
                               int referencesAmbiguous) {

    public int totalReferences() {
        return referencesResolved + referencesUnresolved + referencesAmbiguous;
    }

    /** Percentage of detected references that were resolved (100 when there are none). */
    public int resolutionRatePercent() {
        int total = totalReferences();
        return total == 0 ? 100 : (int) Math.round(100.0 * referencesResolved / total);
    }

    /** A scan is partial if anything was skipped/failed/unreadable or any reference is unresolved. */
    public boolean isPartial() {
        return filesSkipped > 0 || filesFailed > 0 || filesUnreadable > 0
                || referencesUnresolved > 0 || referencesAmbiguous > 0;
    }
}
