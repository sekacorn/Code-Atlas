package com.codeatlas.analysis;

import com.codeatlas.model.SoftwareModel;

/**
 * Facade that runs every analyzer over the model and returns a single
 * {@link AnalysisResult}. Purely deterministic and offline.
 */
public final class AnalysisEngine {

    private final MetricsEngine metrics = new MetricsEngine();
    private final ComplexityAnalyzer complexity;
    private final DeadCodeDetector deadCode;
    private final DependencyAnalyzer dependencies = new DependencyAnalyzer();

    public AnalysisEngine(int complexityThreshold, int deadCodeMinConfidence) {
        this.complexity = new ComplexityAnalyzer(complexityThreshold);
        this.deadCode = new DeadCodeDetector(deadCodeMinConfidence);
    }

    public AnalysisEngine() {
        this(10, 60);
    }

    public AnalysisResult analyze(SoftwareModel model) {
        return new AnalysisResult(
                metrics.compute(model),
                complexity.analyze(model),
                deadCode.detect(model),
                dependencies.analyze(model));
    }
}
