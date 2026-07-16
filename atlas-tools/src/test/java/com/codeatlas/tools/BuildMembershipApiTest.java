package com.codeatlas.tools;

import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build membership through the read-only API, and the honesty rule that a reference
 * the Linker resolved is never also reported as unresolved.
 */
class BuildMembershipApiTest {

    /** A two-module Maven build: one in-repository dependency and one third-party. */
    private Path scannedBuildIndex(Path repo, Path indexDir) throws IOException {
        Files.createDirectories(repo.resolve("core/src"));
        Files.createDirectories(repo.resolve("app/src"));
        Files.writeString(repo.resolve("core/pom.xml"), """
                <project><groupId>com.example</groupId><artifactId>mission-core</artifactId>
                <version>1.0.0</version></project>
                """);
        Files.writeString(repo.resolve("app/pom.xml"), """
                <project>
                    <groupId>com.example</groupId><artifactId>mission-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId><artifactId>mission-core</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(repo.resolve("app/src/App.java"),
                "package com.example.app;\npublic class App { void run() {} }\n");
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.builder().indexPath(index).build());
        return index;
    }

    @Test
    void buildMembershipResolvesAnEntityToItsOwningModule(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedBuildIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var membership = api.getBuildMembership("java:type:com.example.app.App");
            assertTrue(membership.supported(), "build membership is implemented");
            assertEquals(1, membership.value().size());
            assertEquals("build:module:com.example:mission-app",
                    membership.value().get(0).entity().stableId(),
                    "an entity belongs to the module whose directory owns its file");
        }
    }

    @Test
    void aResolvedReferenceIsNeverAlsoReportedAsUnresolved(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        // The Linker keeps each original symbolic edge beside the resolved edge it
        // produces. Counting those originals as unresolved would make the API
        // contradict the scan's own coverage numbers, and would list in-repository
        // modules as third-party dependencies.
        Path index = scannedBuildIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            List<String> unresolved = api.getUnresolvedReferences(500).value().stream()
                    .map(Views.UnresolvedReference::targetName).toList();
            assertFalse(unresolved.contains("com.example:mission-core"),
                    "the in-repository module dependency resolved, so it is not unresolved");
            assertTrue(unresolved.contains("org.slf4j:slf4j-api"),
                    "a genuinely third-party coordinate stays visible as unresolved");

            var counts = api.getReferenceCounts().value();
            assertTrue(counts.resolved() > 0, "the resolved module dependency is counted resolved");
            assertTrue(counts.unresolved() > 0, "the third-party coordinate is counted unresolved");
        }
    }
}
