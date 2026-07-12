package com.codeatlas.analysis;

import com.codeatlas.analysis.lineage.LineageSummary;

import java.util.List;

/**
 * The complete deterministic analysis of a repository: the single object the
 * reporting layer and (optionally) the AI layer consume.
 */
public record AnalysisResult(RepositoryMetrics metrics,
                             List<ComplexityHotspot> complexityHotspots,
                             List<DeadCodeCandidate> deadCode,
                             DependencyAnalysis dependencies,
                             LineageSummary lineage) {

    /** Percentage of candidate entities flagged as probable dead code (headline stat). */
    public int deadCodePercent() {
        int denom = metrics.countOf(com.codeatlas.model.EntityKind.METHOD)
                + metrics.countOf(com.codeatlas.model.EntityKind.FUNCTION)
                + metrics.countOf(com.codeatlas.model.EntityKind.PROCEDURE)
                + metrics.countOf(com.codeatlas.model.EntityKind.CLASS);
        return denom == 0 ? 0 : (int) Math.round(100.0 * deadCode.size() / denom);
    }
}
