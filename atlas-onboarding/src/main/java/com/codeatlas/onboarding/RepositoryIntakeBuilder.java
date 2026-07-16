package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.RepositoryIntake;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 1: repository identity and scan provenance. Uses only the scan's own facts
 * plus the sanitized identity the caller supplied - it never reads the repository,
 * and it does not expose full filesystem paths.
 */
final class RepositoryIntakeBuilder {

    private final AtlasToolApi api;
    private final OnboardingOptions options;

    RepositoryIntakeBuilder(AtlasToolApi api, OnboardingOptions options) {
        this.api = api;
        this.options = options;
    }

    RepositoryIntake build() {
        Views.RepositorySummaryView summary = api.getRepositorySummary().value();
        List<String> languages = new ArrayList<>(new TreeSet<>(summary.filesByLanguage().keySet()));
        return new RepositoryIntake(options.repositoryKey(), options.repositoryDisplayName(),
                options.sanitizedLocation(), options.branch(), api.scanId(), options.storageMode(),
                options.toolVersion(), api.schemaVersion(), languages, buildSystems(),
                summary.totalFiles());
    }

    /**
     * Build systems, taken from the modules the build parser actually understood —
     * a parsed module is stronger evidence than a file name. Build files with no
     * parser (Make/CMake) are still surfaced by name, marked as unparsed.
     */
    private List<String> buildSystems() {
        TreeSet<String> systems = new TreeSet<>();
        for (Views.EntityView m : api.searchEntities("", "MODULE", null, CANDIDATE_CAP).value()) {
            switch (m.attributes().getOrDefault("buildSystem", "")) {
                case "maven" -> systems.add("Maven");
                case "gradle" -> systems.add("Gradle");
                case "gnat" -> systems.add("GNAT project (GPRbuild)");
                default -> {
                }
            }
        }
        for (Views.EntityView f : api.searchEntities("", "FILE", null, CANDIDATE_CAP).value()) {
            String n = f.name().toLowerCase(Locale.ROOT);
            if (n.equals("makefile") || n.equals("cmakelists.txt")) {
                systems.add("Make/CMake (not parsed)");
            }
        }
        return new ArrayList<>(systems);
    }
}
