package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 1 output: the repository's identity and scan provenance. Full filesystem
 * paths are deliberately not required here — the sanitized location is a folder
 * name by default so exported reports do not leak sensitive paths.
 *
 * @param repositoryKey    deterministic key distinguishing same-named repositories
 * @param displayName      human-friendly name
 * @param sanitizedLocation a location safe to print
 * @param branch           current branch if safely known, else ""
 * @param scanId           the completed scan being used
 * @param storageMode      "file-backed" or "in-memory"
 * @param toolVersion      Code Atlas version
 * @param schemaVersion    index schema version
 * @param languages        languages detected, sorted
 * @param buildSystems     build systems detected from build files, sorted
 * @param totalFiles       total files in the scan
 */
public record RepositoryIntake(String repositoryKey,
                               String displayName,
                               String sanitizedLocation,
                               String branch,
                               String scanId,
                               String storageMode,
                               String toolVersion,
                               String schemaVersion,
                               List<String> languages,
                               List<String> buildSystems,
                               int totalFiles) {
    public RepositoryIntake {
        languages = List.copyOf(languages);
        buildSystems = List.copyOf(buildSystems);
    }
}
