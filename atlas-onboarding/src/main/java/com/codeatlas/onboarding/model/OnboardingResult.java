package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * The complete onboarding package: every stage's structured output plus the staged
 * execution record and the provenance metadata (scan id, tool version, rule
 * versions, known limitations). This is the single object the report writers
 * render to text, JSON and HTML.
 *
 * <p>Determinism: given the same scan and options, every field here is byte-for-byte
 * reproducible. The only volatile data — per-stage durations — lives inside
 * {@link OnboardingStageResult#durationMillis()} and is excluded from the
 * deterministic JSON body by the report writer.
 */
public record OnboardingResult(String scanId,
                               String status,
                               OnboardingOptions options,
                               RepositoryIntake intake,
                               ScanHealthSummary scanHealth,
                               SystemInventory inventory,
                               List<EntryPointSummary> entryPoints,
                               ArchitectureOrientation orientation,
                               List<BoundarySummary> boundaries,
                               List<RepresentativeLineagePath> lineagePaths,
                               List<CentralComponentSummary> centralComponents,
                               List<OnboardingRisk> risks,
                               List<ReadingRecommendation> readingOrder,
                               List<ExpertQuestion> expertQuestions,
                               FinalSummary summary,
                               List<OnboardingStageResult> stages,
                               String toolVersion,
                               List<String> ruleVersions,
                               List<String> knownLimitations) {

    public OnboardingResult {
        entryPoints = List.copyOf(entryPoints);
        boundaries = List.copyOf(boundaries);
        lineagePaths = List.copyOf(lineagePaths);
        centralComponents = List.copyOf(centralComponents);
        risks = List.copyOf(risks);
        readingOrder = List.copyOf(readingOrder);
        expertQuestions = List.copyOf(expertQuestions);
        stages = List.copyOf(stages);
        ruleVersions = List.copyOf(ruleVersions);
        knownLimitations = List.copyOf(knownLimitations);
    }

    /** Total wall-clock duration across stages (volatile; for the performance section). */
    public long totalDurationMillis() {
        return stages.stream().mapToLong(OnboardingStageResult::durationMillis).sum();
    }
}
