package com.codeatlas.agents;

import com.codeatlas.core.AdaLineageFixtures;
import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.LineageFixtures;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.tools.AtlasToolApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Data-Lineage Investigator Agent: origin/transform/store/consumer answers
 * grounded in traversal results, a numbered confirmed path with per-edge
 * evidence, honest unresolved segments, and deterministic output.
 */
class LineageInvestigatorAgentTest {

    private Path scannedIndex(Path repo, Path indexDir, boolean ada) throws IOException {
        if (ada) {
            AdaLineageFixtures.writeMissionApp(repo);
        } else {
            LineageFixtures.writeCustomerApp(repo);
        }
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline.withDiscoveredParsers().run(repo,
                PipelineConfig.builder().indexPath(index).build());
        return index;
    }

    @Test
    void investigatesTheCustomerTableEndToEnd(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            AgentReport report = new LineageInvestigatorAgent(api).investigate("sql:table:customer");
            assertEquals(6, report.answers().size(), "all lineage questions are answered");

            // The overview renders the addendum's numbered confirmed path, endpoint → table.
            AgentAnswer overview = report.answers().get(0);
            List<String> path = overview.confirmedFacts();
            assertTrue(path.get(0).equals("1. POST /customers"), "path starts at the endpoint: " + path);
            assertTrue(path.get(path.size() - 1).contains("table: customer"),
                    "path ends at the table: " + path);
            assertTrue(path.stream().anyMatch(s -> s.contains("CustomerMapper#toEntity")),
                    "the transformation step is on the path: " + path);
            assertTrue(overview.confidence().startsWith("High"), overview.confidence());
            assertTrue(!overview.evidence().isEmpty(), "path edges carry evidence");

            AgentAnswer origins = report.answers().get(1);
            assertTrue(origins.confirmedFacts().stream().anyMatch(f -> f.contains("POST /customers")),
                    origins.confirmedFacts().toString());

            AgentAnswer transforms = report.answers().get(2);
            assertTrue(transforms.confirmedFacts().stream().anyMatch(f ->
                            f.contains("toEntity") && f.contains("ATLAS-LINEAGE-MAP-001")),
                    "transformers cite their rule: " + transforms.confirmedFacts());

            AgentAnswer storage = report.answers().get(3);
            assertTrue(storage.confirmedFacts().stream().anyMatch(f ->
                    f.contains("itself a data store")), storage.confirmedFacts().toString());

            AgentAnswer consumers = report.answers().get(4);
            assertTrue(consumers.confirmedFacts().stream().anyMatch(f ->
                            f.contains("getCustomer") && f.contains("reads_from")),
                    "the reader is a consumer: " + consumers.confirmedFacts());

            AgentAnswer unresolved = report.answers().get(5);
            assertTrue(unresolved.confirmedFacts().stream().anyMatch(f -> f.contains("AnalyticsClient")),
                    "the external client stays visible: " + unresolved.confirmedFacts());
        }
    }

    @Test
    void investigatesAdaPackageStateWithConsoleOrigin(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, true);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            AgentReport report = new LineageInvestigatorAgent(api)
                    .investigate("ada:variable:Mission_Data.Current_Route");

            AgentAnswer origins = report.answers().get(1);
            assertTrue(origins.confirmedFacts().stream().anyMatch(f ->
                            f.contains("console_input") && f.contains("Load_Route")),
                    "the writer's input source is the origin: " + origins.confirmedFacts());

            AgentAnswer transforms = report.answers().get(2);
            assertTrue(transforms.confirmedFacts().stream().anyMatch(f ->
                            f.contains("Transform_Waypoints")),
                    "the Ada transformation is detected: " + transforms.confirmedFacts());

            AgentAnswer consumers = report.answers().get(4);
            assertTrue(consumers.confirmedFacts().stream().anyMatch(f -> f.contains("Publish_Route")),
                    "the state reader is a consumer: " + consumers.confirmedFacts());

            AgentAnswer unresolved = report.answers().get(5);
            assertTrue(unresolved.confirmedFacts().stream().anyMatch(f -> f.contains("Telemetry")),
                    "the withed-but-absent unit stays visible: " + unresolved.confirmedFacts());
        }
    }

    @Test
    void everyCitationResolvesInTheSameScan(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            AgentReport report = new LineageInvestigatorAgent(api).investigate("sql:table:customer");
            for (AgentAnswer a : report.answers()) {
                for (AgentAnswer.Citation c : a.evidence()) {
                    assertTrue(api.getEntity(c.stableId()).value().isPresent(),
                            "citation must resolve: " + c.stableId() + " in '" + a.question() + "'");
                }
            }
        }
    }

    @Test
    void investigationJsonIsDeterministicAcrossSessions(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        String first;
        String second;
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            first = new LineageInvestigatorAgent(api)
                    .investigate("java:type:com.example.customer.CustomerResponse").toJson();
        }
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            second = new LineageInvestigatorAgent(api)
                    .investigate("java:type:com.example.customer.CustomerResponse").toJson();
        }
        assertEquals(first, second, "investigation output must be byte-identical across sessions");
    }

    @Test
    void unknownTargetsFailHonestly(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LineageInvestigatorAgent(api).investigate("sql:table:no_such_table"));
        }
    }
}
