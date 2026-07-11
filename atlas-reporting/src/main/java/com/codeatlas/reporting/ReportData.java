package com.codeatlas.reporting;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.model.SoftwareModel;

import java.time.Instant;

/**
 * Everything a report needs: the analysis result, coverage, the model (for
 * traceability and the data-flow view) and light metadata about the run.
 */
public record ReportData(String repositoryName,
                         Instant generatedAt,
                         long scanDurationMillis,
                         SoftwareModel model,
                         AnalysisResult analysis,
                         AnalysisCoverage coverage) {
}
