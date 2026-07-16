package com.codeatlas.onboarding.model;

/**
 * Tunables and sanitized repository identity handed to the onboarding coordinator.
 * The coordinator is constructed from a read-only index only; these values carry
 * the caller-supplied context (display name, a sanitized location, limits) so the
 * report can name the repository without the coordinator ever touching its files.
 *
 * @param repositoryDisplayName human-friendly repository name (folder name)
 * @param sanitizedLocation     a location safe to print (folder name, not a full path,
 *                              unless the caller opts in) — see report privacy rules
 * @param repositoryKey         deterministic key distinguishing same-named repositories
 * @param branch                current branch if safely known, else empty
 * @param storageMode           "file-backed" or "in-memory"
 * @param toolVersion           Code Atlas version string
 * @param maxComponents         max central components to review (default 10)
 * @param maxPaths              max representative lineage paths (default 5)
 * @param includeInferred       include inferred lineage edges in sampled paths
 * @param minConfidence         minimum edge confidence for sampled lineage paths
 */
public record OnboardingOptions(String repositoryDisplayName,
                                String sanitizedLocation,
                                String repositoryKey,
                                String branch,
                                String storageMode,
                                String toolVersion,
                                int maxComponents,
                                int maxPaths,
                                boolean includeInferred,
                                double minConfidence) {

    public static final int DEFAULT_MAX_COMPONENTS = 10;
    public static final int DEFAULT_MAX_PATHS = 5;
    public static final double DEFAULT_MIN_CONFIDENCE = 0.40;

    /** Minimal options for tests and headless use. */
    public static OnboardingOptions forRepository(String displayName, String toolVersion) {
        return new OnboardingOptions(displayName, displayName, displayName, "", "file-backed",
                toolVersion, DEFAULT_MAX_COMPONENTS, DEFAULT_MAX_PATHS, false, DEFAULT_MIN_CONFIDENCE);
    }

    public int maxComponentsOrDefault() {
        return maxComponents <= 0 ? DEFAULT_MAX_COMPONENTS : maxComponents;
    }

    public int maxPathsOrDefault() {
        return maxPaths <= 0 ? DEFAULT_MAX_PATHS : maxPaths;
    }
}
