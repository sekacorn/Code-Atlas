package com.codeatlas.onboarding;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 2: classify how well the scan understood the repository, with documented,
 * deterministic thresholds (never hiding incomplete analysis).
 *
 * <p>Exact file buckets (analyzed/reused/skipped/failed) are only available when
 * onboarding ran the scan itself; when reusing a persisted scan, file counts are
 * derived from the model and the reference-resolution rate is derived from the
 * persisted cross-references - labelled as derived, never presented as exact.
 *
 * <p>Thresholds: {@code FAILED} when no files were analyzed; {@code POOR} when the
 * resolution rate is below 60% or (with exact counts) more than a quarter of files
 * failed to parse; {@code PARTIAL} when anything was skipped/failed or any
 * reference is unresolved/ambiguous; otherwise {@code HEALTHY}.
 */
final class ScanHealthAssessor {

    static final int POOR_RESOLUTION_PERCENT = 60;
    static final int POOR_FAILED_FILES_RATIO = 4; // failed*4 > discovered  ==  >25% failed

    private final AtlasToolApi api;

    ScanHealthAssessor(AtlasToolApi api) {
        this.api = api;
    }

    ScanHealthSummary assess(AnalysisCoverage exactCoverage) {
        if (exactCoverage != null) {
            return fromExact(exactCoverage);
        }
        return derived();
    }

    private ScanHealthSummary fromExact(AnalysisCoverage c) {
        List<String> reasons = new ArrayList<>();
        String status = classify(c.filesAnalyzed(), c.resolutionRatePercent(), c.filesDiscovered(),
                c.filesFailed(), c.isPartial(), c.referencesUnresolved(), c.referencesAmbiguous(), reasons, true);
        return new ScanHealthSummary(status, c.filesDiscovered(), c.filesAnalyzed(), c.filesReused(),
                c.filesSkipped(), c.filesFailed(), c.unsupportedFileTypes(),
                c.referencesResolved(), c.referencesUnresolved(), c.referencesAmbiguous(),
                c.resolutionRatePercent(), reasons, true);
    }

    private ScanHealthSummary derived() {
        Views.ReferenceCounts refs = api.getReferenceCounts().value();
        int filesDiscovered = api.searchEntities("", "FILE", null, CANDIDATE_CAP).totalMatches();
        int filesAnalyzed = (int) api.searchEntities("", "FILE", null, CANDIDATE_CAP).value().stream()
                .filter(f -> f.language().equals("java") || f.language().equals("ada"))
                .count();
        boolean partialish = refs.unresolved() > 0 || refs.ambiguous() > 0 || filesAnalyzed < filesDiscovered;
        List<String> reasons = new ArrayList<>();
        reasons.add("file counts and resolution rate derived from the persisted model (reused scan)");
        String status = classify(filesAnalyzed, refs.resolutionRatePercent(), filesDiscovered,
                -1, partialish, refs.unresolved(), refs.ambiguous(), reasons, false);
        return new ScanHealthSummary(status, filesDiscovered, filesAnalyzed, -1, -1, -1, -1,
                refs.resolved(), refs.unresolved(), refs.ambiguous(), refs.resolutionRatePercent(),
                reasons, false);
    }

    private String classify(int filesAnalyzed, int resolutionPercent, int filesDiscovered,
                            int filesFailed, boolean partial, int unresolved, int ambiguous,
                            List<String> reasons, boolean exact) {
        if (filesAnalyzed <= 0) {
            reasons.add("no files were analyzed");
            return ScanHealthSummary.FAILED;
        }
        reasons.add(resolutionPercent + "% of references resolved");
        boolean poorFailed = exact && filesDiscovered > 0
                && filesFailed * POOR_FAILED_FILES_RATIO > filesDiscovered;
        if (resolutionPercent < POOR_RESOLUTION_PERCENT || poorFailed) {
            if (resolutionPercent < POOR_RESOLUTION_PERCENT) {
                reasons.add("resolution rate below " + POOR_RESOLUTION_PERCENT + "%");
            }
            if (poorFailed) {
                reasons.add(filesFailed + " of " + filesDiscovered + " files failed to parse (>25%)");
            }
            return ScanHealthSummary.POOR;
        }
        if (partial) {
            if (unresolved > 0) {
                reasons.add(unresolved + " unresolved reference(s)");
            }
            if (ambiguous > 0) {
                reasons.add(ambiguous + " ambiguous reference(s)");
            }
            if (exact && filesFailed > 0) {
                reasons.add(filesFailed + " file(s) failed parsing");
            }
            return ScanHealthSummary.PARTIAL;
        }
        reasons.add("all analyzed references resolved");
        return ScanHealthSummary.HEALTHY;
    }
}
