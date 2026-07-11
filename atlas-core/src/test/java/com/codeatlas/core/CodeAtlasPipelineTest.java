package com.codeatlas.core;

import com.codeatlas.model.EntityKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeAtlasPipelineTest {

    @Test
    void analysesMixedJavaAndAdaRepository(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("java/com/x"));
        Files.writeString(repo.resolve("java/com/x/Service.java"), """
                package com.x;
                public class Service {
                    public void run() { helper(); }
                    private void helper() {}
                    private void neverUsed() {}
                }
                """);
        Files.createDirectories(repo.resolve("ada"));
        Files.writeString(repo.resolve("ada/nav.adb"), """
                package body Nav is
                   procedure Start is
                   begin
                      Init;
                   end Start;
                   procedure Init is
                   begin
                      null;
                   end Init;
                   procedure Orphan is
                   begin
                      null;
                   end Orphan;
                end Nav;
                """);

        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.defaults());

        // Both languages parsed.
        var langs = result.analysis().metrics().filesByLanguage();
        assertEquals(1, langs.get("java"));
        assertEquals(1, langs.get("ada"));

        // Cross-references were linked: helper() and Init are called, so they must
        // NOT be dead; neverUsed() and Orphan should surface as candidates.
        var deadNames = result.analysis().deadCode().stream()
                .map(c -> c.qualifiedName()).toList();
        assertTrue(deadNames.stream().anyMatch(n -> n.contains("neverUsed")), "neverUsed should be dead: " + deadNames);
        assertTrue(deadNames.stream().anyMatch(n -> n.contains("Orphan")), "Orphan should be dead: " + deadNames);
        assertTrue(deadNames.stream().noneMatch(n -> n.contains("helper")), "helper is called, not dead");
        assertTrue(deadNames.stream().noneMatch(n -> n.endsWith(".Init")), "Init is called, not dead");

        // Model has a PROJECT root and FILE entities.
        assertEquals(1, result.model().entitiesOfKind(EntityKind.PROJECT).size());
        assertEquals(2, result.model().entitiesOfKind(EntityKind.FILE).size());
    }
}
