package com.codeatlas.reporting;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.model.SoftwareModel;

import java.time.Instant;

/**
 * Everything a report needs: the analysis result, coverage, the model (for
 * traceability and the data-flow view) and light metadata about the run.
 *
 * <p>{@code scanId} is content-derived and deterministic; {@code generatedAt} is
 * the only non-deterministic field and is excluded from determinism comparisons.
 */
public record ReportData(String repositoryName,
                         Instant generatedAt,
                         long scanDurationMillis,
                         String scanId,
                         SoftwareModel model,
                         AnalysisResult analysis,
                         AnalysisCoverage coverage) {
}
