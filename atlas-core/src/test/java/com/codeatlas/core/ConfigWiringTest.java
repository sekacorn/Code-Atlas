package com.codeatlas.core;

import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: configuration parsing links config to code so a framework-wired
 * class is no longer reported as dead, and its wiring is resolved and queryable.
 */
class ConfigWiringTest {

    @Test
    void xmlWiredBeanIsNotDeadCode(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src/com/x"));
        // A package-private, uncalled class: pure static analysis would call it dead…
        Files.writeString(repo.resolve("src/com/x/LegacyJob.java"), """
                package com.x;
                class LegacyJob {
                    void run() { }
                }
                """);
        Files.createDirectories(repo.resolve("conf"));
        Files.writeString(repo.resolve("conf/beans.xml"), """
                <beans>
                    <bean id="job" class="com.x.LegacyJob"/>
                </beans>
                """);

        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.defaults());
        SoftwareModel model = result.model();

        // The CONFIGURES edge resolved from the config file to the class, keeping
        // its source location and config-key evidence through resolution.
        com.codeatlas.model.Relationship configures = model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.CONFIGURES && r.resolved()
                        && r.fromId().equals("config:conf/beans.xml")
                        && r.toId().equals("java:type:com.x.LegacyJob"))
                .findFirst().orElseThrow(() -> new AssertionError("config must resolve to the wired class"));
        assertTrue(configures.location().isPresent(), "resolved config edge keeps its location");
        assertTrue(configures.attributes().containsKey("configKey"), "resolved config edge keeps its key");

        // …but because config references it, it is not flagged as dead.
        assertFalse(result.analysis().deadCode().stream()
                        .anyMatch(c -> c.qualifiedName().equals("com.x.LegacyJob")),
                "a config-wired class must not be reported as dead code");

        // The config file was analyzed (not skipped) in coverage.
        assertTrue(result.coverage().filesAnalyzed() >= 2, "the XML file is analyzed");
    }
}
