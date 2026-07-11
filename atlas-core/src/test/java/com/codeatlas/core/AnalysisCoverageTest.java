package com.codeatlas.core;

import com.codeatlas.analysis.AnalysisCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisCoverageTest {

    @Test
    void reportsSkippedFilesAndUnresolvedReferencesHonestly(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src"));
        // One parseable Java file that calls an external, unresolvable method.
        Files.writeString(repo.resolve("src/App.java"), """
                package a;
                public class App {
                    public void run() { System.out.println("hi"); }
                }
                """);
        // One file with no parser (unsupported type) -> must be counted as skipped.
        Files.writeString(repo.resolve("README.md"), "# docs\nsome text\n");

        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.defaults());
        AnalysisCoverage cov = result.coverage();

        assertEquals(2, cov.filesDiscovered());
        assertEquals(1, cov.filesAnalyzed(), "only the Java file has a parser");
        assertEquals(1, cov.filesSkipped(), "the markdown file has no parser");
        assertTrue(cov.unsupportedFileTypes() >= 1);

        // println() targets an external type not in the model -> unresolved reference.
        assertTrue(cov.referencesUnresolved() >= 1, "external calls must surface as unresolved");
        assertTrue(cov.isPartial(), "a scan with skips/unresolved refs is partial");
        assertTrue(cov.resolutionRatePercent() <= 100 && cov.resolutionRatePercent() >= 0);
    }

    @Test
    void coverageIsCompleteWhenEverythingResolves(@TempDir Path repo) throws IOException {
        // A self-contained Java file whose only call resolves internally.
        Files.writeString(repo.resolve("Self.java"), """
                public class Self {
                    void a() { b(); }
                    void b() { }
                }
                """);
        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.defaults());
        AnalysisCoverage cov = result.coverage();
        assertEquals(1, cov.filesAnalyzed());
        assertEquals(0, cov.filesSkipped());
        assertEquals(0, cov.referencesUnresolved(), "b() resolves to the local method");
        assertEquals(100, cov.resolutionRatePercent());
    }
}
