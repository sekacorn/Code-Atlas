package com.codeatlas.core;

import com.codeatlas.index.AtlasStore;
import com.codeatlas.model.Entity;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the persistent-scan architecture: conservative reuse of
 * unchanged parser results, correct reaction to changed and deleted files,
 * persistence across restart, and failed-scan protection.
 */
class IncrementalReuseTest {

    private static void writeFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/Service.java"), """
                package com.x;
                public class Service {
                    public void run() { helper(); }
                    private void helper() { }
                }
                """);
        Files.writeString(repo.resolve("src/Util.java"), """
                package com.x;
                public class Util {
                    public static int twice(int v) { return v * 2; }
                }
                """);
    }

    private static PipelineConfig configWith(Path index) {
        return PipelineConfig.builder().indexPath(index).build();
    }

    @Test
    void secondScanReusesAllUnchangedFilesAndStaysIdentical(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        writeFixture(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();

        PipelineResult first = pipeline.run(repo, configWith(index));
        assertEquals(0, first.coverage().filesReused(), "first scan has nothing to reuse");
        assertEquals(2, first.coverage().filesAnalyzed());

        PipelineResult second = pipeline.run(repo, configWith(index));
        assertEquals(2, second.coverage().filesReused(), "unchanged files must be reused");
        assertEquals(2, second.coverage().filesAnalyzed(), "reused files still count as analyzed");
        assertEquals(first.scanId(), second.scanId(), "identical content -> identical scan id");
        assertEquals(ids(first.model()), ids(second.model()),
                "reused facts must be indistinguishable from re-parsing");
        assertEquals(2, second.changes().unchanged().size());
    }

    @Test
    void changedFileIsReparsedAndItsFactsReplaced(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeFixture(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        pipeline.run(repo, configWith(index));

        // Rename a method: stale facts must disappear, new facts must appear.
        Files.writeString(repo.resolve("src/Util.java"), """
                package com.x;
                public class Util {
                    public static int thrice(int v) { return v * 3; }
                }
                """);
        PipelineResult after = pipeline.run(repo, configWith(index));

        assertEquals(1, after.coverage().filesReused(), "only the unchanged file is reused");
        assertEquals(1, after.changes().changed().size());
        assertTrue(after.model().entity("java:method:com.x.Util#thrice(int)").isPresent());
        assertFalse(after.model().entity("java:method:com.x.Util#twice(int)").isPresent(),
                "stale facts from the old version must be gone");
    }

    @Test
    void deletedFileFactsAreRemoved(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeFixture(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        pipeline.run(repo, configWith(index));

        Files.delete(repo.resolve("src/Util.java"));
        PipelineResult after = pipeline.run(repo, configWith(index));

        assertEquals(1, after.changes().removed().size());
        assertFalse(after.model().entity("java:type:com.x.Util").isPresent(),
                "facts from deleted files must not survive");
        assertTrue(after.model().entity("java:type:com.x.Service").isPresent());
    }

    @Test
    void persistedScanSurvivesRestart(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        writeFixture(repo);
        Path index = indexDir.resolve("atlas");
        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers().run(repo, configWith(index));

        // "Restart": open the index fresh, without rescanning.
        try (AtlasStore reopened = AtlasStore.atPath(index)) {
            var latest = reopened.latestCompletedScan().orElseThrow();
            assertEquals(result.scanId(), latest.scanKey());
            SoftwareModel loaded = reopened.loadModel();
            assertTrue(loaded.entity("java:type:com.x.Service").isPresent(),
                    "model must be queryable after restart without a rescan");
            assertEquals(result.model().entityCount(), loaded.entityCount());
        }
    }

    @Test
    void failedScanLeavesPreviousCompletedScanReadable(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        writeFixture(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        PipelineResult good = pipeline.run(repo, configWith(index));

        // A scan of a root that disappears fails before any persistence.
        Path gone = repo.resolve("does-not-exist");
        assertThrows(IllegalArgumentException.class, () -> pipeline.run(gone, configWith(index)));

        try (AtlasStore reopened = AtlasStore.atPath(index)) {
            assertEquals(good.scanId(), reopened.latestCompletedScan().orElseThrow().scanKey(),
                    "a failed scan must not replace the last completed scan");
            assertTrue(reopened.loadModel().entity("java:type:com.x.Service").isPresent());
        }
    }

    private static List<String> ids(SoftwareModel model) {
        return model.entities().stream().map(Entity::id).sorted().toList();
    }
}
