package com.codeatlas.analysis;

import com.codeatlas.model.EntityKind;

import java.util.Map;

/**
 * Headline size-and-shape numbers for the whole repository &mdash; the data behind
 * the dashboard's "how large is the system" answer. Everything here is derived
 * deterministically from the model; no estimates, no AI.
 */
public record RepositoryMetrics(int totalFiles,
                                long totalLines,
                                long codeLines,
                                long commentLines,
                                long blankLines,
                                Map<String, Integer> filesByLanguage,
                                Map<EntityKind, Integer> entityCounts) {

    public RepositoryMetrics {
        filesByLanguage = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(filesByLanguage));
        entityCounts = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(entityCounts));
    }

    public int countOf(EntityKind kind) {
        return entityCounts.getOrDefault(kind, 0);
    }

    /** Comment-to-code ratio as a percentage (0 when there is no code). */
    public int commentRatioPercent() {
        long denom = codeLines + commentLines;
        return denom == 0 ? 0 : (int) Math.round(100.0 * commentLines / denom);
    }
}
