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
 * The deterministic Repository Orientation Agent and entity summaries: answers
 * follow the required structure, cite resolvable evidence, keep confirmed facts
 * separate from inferred findings, and are byte-identical across sessions.
 */
class OrientationAgentTest {

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
    void orientationAnswersAllQuestionsWithCitedEvidence(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            OrientationReport report = new OrientationAgent(api).orient();
            assertEquals(8, report.answers().size(), "all eight orientation questions are answered");
            assertEquals(api.scanId(), report.scanId());

            AgentAnswer start = report.answers().get(0);
            assertTrue(start.answer().contains("HTTP endpoints"), start.answer());
            assertTrue(start.confirmedFacts().stream().anyMatch(f ->
                            f.contains("POST /customers")
                                    && f.contains("CustomerController#createCustomer")),
                    "the handler must be resolved from the graph: " + start.confirmedFacts());

            // Every cited stable id must resolve in the same scan — no invented evidence.
            for (AgentAnswer a : report.answers()) {
                for (AgentAnswer.Citation c : a.evidence()) {
                    assertTrue(api.getEntity(c.stableId()).value().isPresent(),
                            "citation must resolve: " + c.stableId() + " in '" + a.question() + "'");
                }
                assertTrue(!a.confidence().isBlank(), "confidence is mandatory");
            }
        }
    }

    @Test
    void confirmedFactsAndInferencesStaySeparated(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            OrientationReport report = new OrientationAgent(api).orient();

            AgentAnswer external = report.answers().stream()
                    .filter(a -> a.question().contains("external systems")).findFirst().orElseThrow();
            assertTrue(external.inferredFindings().stream().anyMatch(f -> f.contains("AnalyticsClient")),
                    "the absent client is inferred external: " + external.inferredFindings());
            assertTrue(external.inferredFindings().stream().allMatch(f -> f.contains("(inferred)")),
                    "inferences must be labelled");
            assertTrue(external.confidence().startsWith("Low"),
                    "inference-only answers carry low confidence: " + external.confidence());

            AgentAnswer central = report.answers().stream()
                    .filter(a -> a.question().contains("most central")).findFirst().orElseThrow();
            assertTrue(central.confirmedFacts().stream().anyMatch(f ->
                    f.contains("CustomerEntity") && f.contains("dependent")), central.confirmedFacts().toString());
            assertTrue(central.inferredFindings().isEmpty(), "centrality counts are facts, not inferences");
        }
    }

    @Test
    void analysisGapsAreReportedNotHidden(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            AgentAnswer gaps = new OrientationAgent(api).orient().answers().get(7);
            assertTrue(gaps.confirmedFacts().stream().anyMatch(f -> f.contains("could not be resolved")),
                    gaps.confirmedFacts().toString());
            assertTrue(gaps.knownLimitations().stream().anyMatch(l -> l.contains("not implemented")),
                    "unsupported capabilities must be quoted: " + gaps.knownLimitations());
        }
    }

    @Test
    void orientationJsonIsDeterministicAcrossSessions(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        String first;
        String second;
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            first = new OrientationAgent(api).orient().toJson();
        }
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            second = new OrientationAgent(api).orient().toJson();
        }
        assertEquals(first, second, "orientation output must be byte-identical across sessions");
    }

    @Test
    void adaOrientationFindsStateSourcesAndExternals(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, true);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            List<AgentAnswer> answers = new OrientationAgent(api).orient().answers();

            AgentAnswer entries = answers.get(2);
            assertTrue(entries.inferredFindings().stream().anyMatch(f ->
                            f.contains("Load_Route") && f.contains("console_input")),
                    "console reader is an inferred entry point: " + entries.inferredFindings());

            AgentAnswer stores = answers.get(4);
            assertTrue(stores.confirmedFacts().stream().anyMatch(f ->
                            f.contains("Mission_Data.Current_Route")),
                    "package state counts as a data store: " + stores.confirmedFacts());

            AgentAnswer external = answers.get(5);
            assertTrue(external.confirmedFacts().stream().anyMatch(f -> f.contains("console_input")),
                    "console source is a confirmed external interface");
            assertTrue(external.inferredFindings().stream().anyMatch(f -> f.contains("Telemetry")),
                    "the withed-but-absent unit is inferred external: " + external.inferredFindings());
        }
    }

    @Test
    void methodSummaryStatesEffectsCallsAndLineageRole(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            EntitySummarizer summarizer = new EntitySummarizer(api);

            AgentAnswer create = summarizer.summarize(
                    "java:method:com.example.customer.CustomerService#createCustomer(CustomerRequest)");
            assertTrue(create.confirmedFacts().contains("parameters: CustomerRequest"));
            assertTrue(create.confirmedFacts().stream().anyMatch(f ->
                    f.equals("writes to DATABASE_OBJECT customer")), create.confirmedFacts().toString());
            assertTrue(create.confirmedFacts().stream().anyMatch(f ->
                    f.contains("calls com.example.customer.CustomerMapper#toEntity")), "calls are listed");

            AgentAnswer handler = summarizer.summarize(
                    "java:method:com.example.customer.CustomerController#createCustomer(CustomerRequest)");
            assertTrue(handler.confirmedFacts().stream().anyMatch(f -> f.contains("handles POST /customers")),
                    handler.confirmedFacts().toString());

            AgentAnswer mapper = summarizer.summarize(
                    "java:method:com.example.customer.CustomerMapper#toEntity(CustomerRequest)");
            assertTrue(mapper.inferredFindings().stream().anyMatch(f -> f.contains("transformation")),
                    "transformation role is labelled inferred: " + mapper.inferredFindings());
            assertTrue(mapper.confirmedFacts().stream().anyMatch(f ->
                    f.contains("produces com.example.customer.CustomerEntity")));

            assertThrows(IllegalArgumentException.class, () -> summarizer.summarize("java:type:does.Not.Exist"));
        }
    }

    @Test
    void componentSummaryInfersResponsibilityFromStructure(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedIndex(repo, indexDir, false);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            EntitySummarizer summarizer = new EntitySummarizer(api);

            AgentAnswer service = summarizer.summarize("java:type:com.example.customer.CustomerService");
            assertTrue(service.inferredFindings().stream().anyMatch(f ->
                            f.contains("business/service logic") && f.contains("(inferred")),
                    service.inferredFindings().toString());
            assertTrue(service.confirmedFacts().stream().anyMatch(f -> f.startsWith("contains ")),
                    "member count is a confirmed fact");
            assertTrue(service.confirmedFacts().stream().anyMatch(f ->
                    f.contains("used by com.example.customer.CustomerController")), "consumers are listed");

            AgentAnswer repoSummary = summarizer.summarize("java:type:com.example.customer.CustomerRepository");
            assertTrue(repoSummary.inferredFindings().stream().anyMatch(f -> f.contains("data access")),
                    repoSummary.inferredFindings().toString());
        }
    }
}
