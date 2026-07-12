package com.codeatlas.tools;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.core.AdaLineageFixtures;
import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.LineageFixtures;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.index.IndexException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent tool boundary: read-only behavior, evidence citation, honest
 * capability reporting and deterministic output over the persisted index.
 */
class AtlasToolApiTest {

    private static final String SERVICE_CREATE =
            "java:method:com.example.customer.CustomerService#createCustomer(CustomerRequest)";
    private static final String TABLE = "sql:table:customer";

    private Path scannedCustomerIndex(Path repo, Path indexDir) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline.withDiscoveredParsers().run(repo,
                PipelineConfig.builder().indexPath(index).build());
        return index;
    }

    @Test
    void toolSessionsAreReadOnlyAtEveryLevel(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);

        int entitiesBefore;
        int relationshipsBefore;
        try (AtlasStore store = AtlasStore.atPath(index)) {
            var model = store.loadModel();
            entitiesBefore = model.entityCount();
            relationshipsBefore = model.relationshipCount();
        }

        // A full tool session across every operation…
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            api.getRepositorySummary();
            api.findEntity("POST /customers");
            api.searchEntities("Customer", null, null, 10);
            api.getCallers(SERVICE_CREATE, 10);
            api.getCallees(SERVICE_CREATE, 10);
            api.getDependents(TABLE, 10);
            api.getDatabaseReferences(null, 10);
            api.traceDataLineage(LineageQuery.downstream("java:endpoint:POST:/customers"));
            api.calculateChangeImpact("java:type:com.example.customer.CustomerEntity", 5, 20);
            api.findDeadCodeCandidates(10);
            api.getComplexity(10);
            api.getUnresolvedReferences(10);
            api.getDiagnostics();
            api.getSourceEvidence(SERVICE_CREATE);
        }

        // …leaves the persisted model byte-for-byte intact.
        try (AtlasStore store = AtlasStore.atPath(index)) {
            var model = store.loadModel();
            assertEquals(entitiesBefore, model.entityCount(), "tool session must not add entities");
            assertEquals(relationshipsBefore, model.relationshipCount(), "tool session must not add edges");
        }

        // And the storage layer itself rejects writes on a read-only handle.
        try (AtlasStore readOnly = AtlasStore.atPathReadOnly(index)) {
            assertTrue(readOnly.isReadOnly());
            assertThrows(IndexException.class, () -> readOnly.saveHashes(Map.of("x", "y")),
                    "the database engine must reject writes through the read-only handle");
        }
    }

    @Test
    void everyAnswerCitesEvidenceAndStableIds(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            assertFalse(api.scanId().isBlank(), "every session is pinned to a scan");

            var endpoint = api.findEntity("POST /customers").value().orElseThrow();
            assertEquals("java:endpoint:POST:/customers", endpoint.stableId());
            assertTrue(endpoint.location().contains("CustomerController.java"),
                    "entities cite their source location");

            var callers = api.getCallers(SERVICE_CREATE, 10);
            assertFalse(callers.value().isEmpty());
            for (Views.NeighborView n : callers.value()) {
                assertFalse(n.entity().stableId().isBlank());
                assertFalse(n.edge().status().isBlank(), "edges carry resolution status");
                assertFalse(n.edge().ruleId().isBlank(), "lineage edges carry their rule id");
                assertFalse(n.edge().evidence().isBlank(), "edges cite file:line evidence");
            }

            var evidence = api.getSourceEvidence(SERVICE_CREATE).value().orElseThrow();
            assertTrue(evidence.location().contains("CustomerService.java"));
            assertEquals(64, evidence.fileHash().length(), "evidence includes the file content hash");

            var dead = api.findDeadCodeCandidates(10);
            assertTrue(dead.value().stream().allMatch(c -> !c.stableId().isBlank()
                    && !c.evidence().isEmpty()), "dead-code findings carry ids and evidence lists");
        }
    }

    @Test
    void impactAnalysisIsEvidenceBasedAndHonest(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var impact = api.calculateChangeImpact("java:type:com.example.customer.CustomerEntity", 5, 50)
                    .value();
            assertTrue(impact.directDependents().stream().anyMatch(n ->
                            n.entity().stableId().contains("CustomerRepository")),
                    "the repository manages the entity → direct dependent");
            assertTrue(impact.directDependents().stream().anyMatch(n ->
                            n.entity().stableId().contains("CustomerMapper")),
                    "the mapper produces the entity → direct dependent");
            assertFalse(impact.indirectDependents().isEmpty(), "transitive dependents are reported");
            assertTrue(impact.downstreamLineage().contains(TABLE),
                    "the entity's table is downstream lineage impact");
            assertTrue(impact.limitations().toLowerCase().contains("build"),
                    "impact must state its blind spots");
        }
    }

    @Test
    void unsupportedOperationsSaySoInsteadOfReturningEmpty(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var build = api.getBuildMembership("java:type:com.example.customer.CustomerEntity");
            assertFalse(build.supported(), "missing capability must be explicit");
            assertTrue(build.note().contains("not implemented"));
            // Configuration references are now supported (this fixture has no config,
            // so the answer is an empty-but-supported list, not "unsupported").
            var config = api.getConfigurationReferences(null, 50);
            assertTrue(config.supported(), "configuration references are implemented");
        }
    }

    @Test
    void ambiguityAndUnresolvedReferencesStayVisible(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var ambiguous = api.findEntity("send");
            assertTrue(ambiguous.value().isEmpty(), "ambiguous lookups never pick arbitrarily");
            assertTrue(ambiguous.note().contains("ambiguous"), ambiguous.note());

            var unresolved = api.getUnresolvedReferences(200);
            assertTrue(unresolved.value().stream().anyMatch(u ->
                            u.targetName().contains("AnalyticsClient") || u.fromId().contains("AnalyticsClient")
                                    || u.targetName().equals("push")),
                    "the external client must appear among unresolved references");
        }
    }

    @Test
    void truncationIsReportedWithTotals(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var page = api.searchEntities("Customer", null, null, 1);
            assertEquals(1, page.value().size());
            assertTrue(page.truncated(), "limits must be visible");
            assertTrue(page.totalMatches() > 1, "totals accompany truncation");
        }
    }

    @Test
    void toolJsonIsDeterministicAcrossSessions(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        Path index = scannedCustomerIndex(repo, indexDir);
        ToolJsonWriter writer = new ToolJsonWriter();
        String first;
        String second;
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            first = writer.render("get_repository_summary", api.getRepositorySummary())
                    + writer.render("get_callers", api.getCallers(SERVICE_CREATE, 10))
                    + writer.render("calculate_change_impact",
                    api.calculateChangeImpact(TABLE, 5, 20));
        }
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            second = writer.render("get_repository_summary", api.getRepositorySummary())
                    + writer.render("get_callers", api.getCallers(SERVICE_CREATE, 10))
                    + writer.render("calculate_change_impact",
                    api.calculateChangeImpact(TABLE, 5, 20));
        }
        assertEquals(first, second, "tool output must be byte-identical across sessions");
    }

    @Test
    void servesAdaLineageEntitiesToo(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline.withDiscoveredParsers().run(repo,
                PipelineConfig.builder().indexPath(index).build());

        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            assertTrue(api.getDataSources().value().stream()
                    .anyMatch(v -> v.stableId().equals("ada:source:console_input")));
            assertTrue(api.getDataSinks().value().stream()
                    .anyMatch(v -> v.stableId().equals("ada:sink:console_output")));

            var writers = api.getDependents("ada:variable:Mission_Data.Current_Route", 10);
            assertTrue(writers.value().stream().anyMatch(n ->
                            n.entity().stableId().equals("ada:procedure:Mission_Data.Load_Route")),
                    "state dependents include the writer procedure");

            var summary = api.getRepositorySummary().value();
            assertTrue(summary.dataSources().contains("console_input"));
        }
    }
}
