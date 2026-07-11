package com.codeatlas.core;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.index.ChangeSet;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.reporting.ReportData;
import com.codeatlas.scanner.ScanResult;

/**
 * Everything one pipeline run produces: the merged model, the analysis, coverage,
 * the raw scan, the change set (incremental view), and a ready-to-render report.
 */
public record PipelineResult(SoftwareModel model,
                             AnalysisResult analysis,
                             AnalysisCoverage coverage,
                             ScanResult scan,
                             ChangeSet changes,
                             ReportData reportData) {
}
