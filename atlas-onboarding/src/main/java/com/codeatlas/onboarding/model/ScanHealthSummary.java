package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 2 output: an honest account of how well the scan understood the repository,
 * classified with documented, deterministic thresholds.
 *
 * <p>Classification (see ONBOARDING.md): {@code FAILED} when no completed scan or no
 * files analyzed; {@code POOR} when the reference-resolution rate is below 60% or
 * more than a quarter of files failed; {@code PARTIAL} when anything was skipped or
 * failed or any reference is unresolved; otherwise {@code HEALTHY}.
 *
 * @param status              HEALTHY / PARTIAL / POOR / FAILED
 * @param filesDiscovered     files found by the scanner (after exclusions)
 * @param filesAnalyzed       files a parser understood
 * @param filesReused         files reused from the parse cache (-1 when not known)
 * @param filesSkipped        files with no parser (-1 when not known from a reused scan)
 * @param filesFailed         files that failed parsing (-1 when not known)
 * @param unsupportedFileTypes distinct unparsed extensions (-1 when not known)
 * @param referencesResolved  references linked to a target
 * @param referencesUnresolved references whose target was not found
 * @param referencesAmbiguous references with too many candidates
 * @param resolutionRatePercent percent of references resolved (100 when none)
 * @param reasons             the human-readable reasons behind the classification
 * @param exactFileCounts     true when file buckets came from a fresh scan (exact);
 *                            false when derived from a reused scan's model
 */
public record ScanHealthSummary(String status,
                                int filesDiscovered,
                                int filesAnalyzed,
                                int filesReused,
                                int filesSkipped,
                                int filesFailed,
                                int unsupportedFileTypes,
                                int referencesResolved,
                                int referencesUnresolved,
                                int referencesAmbiguous,
                                int resolutionRatePercent,
                                List<String> reasons,
                                boolean exactFileCounts) {

    public ScanHealthSummary {
        reasons = List.copyOf(reasons);
    }

    public static final String HEALTHY = "HEALTHY";
    public static final String PARTIAL = "PARTIAL";
    public static final String POOR = "POOR";
    public static final String FAILED = "FAILED";
}
