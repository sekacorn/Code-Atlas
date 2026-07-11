package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization tests: they pin down important <em>existing</em> behaviour so
 * later refactors (stable ids, storage changes, lineage) cannot silently break it.
 * They assert properties the platform promises — determinism, read-only analysis,
 * and traceable evidence — rather than one-off values.
 */
class CharacterizationTest {

    private static void writeFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src/com/x"));
        Files.writeString(repo.resolve("src/com/x/Service.java"), """
                package com.x;
                public class Service {
                    private int count;
                    public int run(int n) {
                        int t = 0;
                        for (int i = 0; i < n; i++) { if (i > count) t += i; }
                        return helper(t);
                    }
                    private int helper(int v) { return v * 2; }
                }
                """);
        Files.writeString(repo.resolve("src/com/x/util.adb"), """
                package body Util is
                   procedure Reset is
                   begin
                      null;
                   end Reset;
                end Util;
                """);
    }

    @Test
    void analysisDoesNotModifyTheRepository(@TempDir Path repo) throws IOException {
        writeFixture(repo);
        Map<String, String> before = hashTree(repo);

        CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());

        Map<String, String> after = hashTree(repo);
        assertEquals(before, after, "scanning must never modify the analyzed repository");
    }

    @Test
    void entityIdentifiersAreDeterministicAcrossRuns(@TempDir Path repo) throws IOException {
        writeFixture(repo);
        List<String> firstRun = sortedEntityIds(repo);
        List<String> secondRun = sortedEntityIds(repo);
        assertEquals(firstRun, secondRun, "the same source must yield the same entity ids");
    }

    @Test
    void everyCodeEntityCarriesTraceableEvidence(@TempDir Path repo) throws IOException {
        writeFixture(repo);
        var result = CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());

        for (Entity e : result.model().entities()) {
            if (e.kind() == EntityKind.PROJECT || e.kind() == EntityKind.PACKAGE) {
                continue; // PROJECT is synthetic; PACKAGE is a logical aggregate
            }
            assertTrue(e.location().isPresent(), "entity lacks source evidence: " + e);
            assertTrue(!e.location().get().filePath().isBlank(), "evidence has no file: " + e);
        }
    }

    private List<String> sortedEntityIds(Path repo) {
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults())
                .model().entities().stream().map(Entity::id).sorted().collect(Collectors.toList());
    }

    private Map<String, String> hashTree(Path root) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                hashes.put(root.relativize(p).toString(), sha256(Files.readAllBytes(p)));
            }
        }
        return hashes;
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
