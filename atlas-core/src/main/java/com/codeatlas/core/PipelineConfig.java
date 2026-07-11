package com.codeatlas.core;

import com.codeatlas.scanner.ScanOptions;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration for one pipeline run. Sensible defaults mean a caller only has to
 * supply the repository root.
 */
public final class PipelineConfig {

    private final ScanOptions scanOptions;
    private final int complexityThreshold;
    private final int deadCodeMinConfidence;
    private final Path indexPath; // null -> in-memory (per spec default)

    private PipelineConfig(Builder b) {
        this.scanOptions = b.scanOptions;
        this.complexityThreshold = b.complexityThreshold;
        this.deadCodeMinConfidence = b.deadCodeMinConfidence;
        this.indexPath = b.indexPath;
    }

    public static PipelineConfig defaults() {
        return builder().build();
    }

    public ScanOptions scanOptions() {
        return scanOptions;
    }

    public int complexityThreshold() {
        return complexityThreshold;
    }

    public int deadCodeMinConfidence() {
        return deadCodeMinConfidence;
    }

    public Optional<Path> indexPath() {
        return Optional.ofNullable(indexPath);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ScanOptions scanOptions = ScanOptions.defaults();
        private int complexityThreshold = 10;
        private int deadCodeMinConfidence = 60;
        private Path indexPath;

        public Builder scanOptions(ScanOptions scanOptions) {
            this.scanOptions = scanOptions;
            return this;
        }

        public Builder complexityThreshold(int complexityThreshold) {
            this.complexityThreshold = complexityThreshold;
            return this;
        }

        public Builder deadCodeMinConfidence(int deadCodeMinConfidence) {
            this.deadCodeMinConfidence = deadCodeMinConfidence;
            return this;
        }

        public Builder indexPath(Path indexPath) {
            this.indexPath = indexPath;
            return this;
        }

        public PipelineConfig build() {
            return new PipelineConfig(this);
        }
    }
}
