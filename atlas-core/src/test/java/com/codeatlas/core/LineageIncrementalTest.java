package com.codeatlas.core;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.analysis.lineage.LineageService;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.reporting.LineageJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lineage under the persistent-scan architecture: reuse keeps results identical,
 * changed files update lineage, deleted files remove it, results survive restart,
 * and a failed scan leaves the previous lineage readable.
 */
class LineageIncrementalTest {

    private static final String ENDPOINT = "java:endpoint:POST:/customers";
    private static final String TABLE = "sql:table:customer";

    private static PipelineConfig config(Path index) {
        return PipelineConfig.builder().indexPath(index).build();
    }

    private static String lineageJson(PipelineResult result) {
        LineageQuery query = LineageQuery.downstream(ENDPOINT);
        LineageResult lineage = new LineageService().trace(result.model(), query);
        return new LineageJsonWriter().render(result.scanId(), query, lineage);
    }

    @Test
    void reusedScanProducesIdenticalLineage(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();

        PipelineResult first = pipeline.run(repo, config(index));
        PipelineResult second = pipeline.run(repo, config(index));

        assertEquals(11, second.coverage().filesReused(), "all fixture files must be reused");
        assertEquals(lineageJson(first), lineageJson(second),
                "lineage from reused facts must be byte-identical to the fresh scan");
    }

    @Test
    void changingTheMapperUpdatesLineage(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        PipelineResult before = pipeline.run(repo, config(index));
        assertTrue(before.model().entity(
                "java:method:com.example.customer.CustomerMapper#toEntity(CustomerRequest)").isPresent());

        // Rename the mapping method: old transformation must vanish, new one appear.
        Files.writeString(repo.resolve("src/main/java/com/example/customer/CustomerMapper.java"), """
                package com.example.customer;

                public class CustomerMapper {

                    CustomerEntity buildEntity(CustomerRequest request) {
                        CustomerEntity entity = new CustomerEntity();
                        return entity;
                    }

                    CustomerResponse toResponse(CustomerEntity entity) {
                        CustomerResponse response = new CustomerResponse();
                        return response;
                    }
                }
                """);
        PipelineResult after = pipeline.run(repo, config(index));

        assertEquals(10, after.coverage().filesReused(), "only the mapper is re-parsed");
        assertFalse(after.model().entity(
                        "java:method:com.example.customer.CustomerMapper#toEntity(CustomerRequest)").isPresent(),
                "stale mapper method must be gone");
        assertTrue(after.model().relationships().stream().anyMatch(r ->
                        r.kind() == RelationshipKind.PRODUCES
                                && r.fromId().equals("java:method:com.example.customer.CustomerMapper#buildEntity(CustomerRequest)")),
                "the renamed transformation must be detected");
    }

    @Test
    void deletingTheRepositoryRemovesTheTableWritePath(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        PipelineResult before = pipeline.run(repo, config(index));
        assertTrue(before.model().relationships().stream()
                .anyMatch(r -> r.kind() == RelationshipKind.WRITES_TO && r.toId().equals(TABLE)));

        Files.delete(repo.resolve("src/main/java/com/example/customer/CustomerRepository.java"));
        PipelineResult after = pipeline.run(repo, config(index));

        assertFalse(after.model().relationships().stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.WRITES_TO && r.toId().equals(TABLE)),
                "without the repository, no write path to the table may be claimed");
        assertTrue(after.model().entity(TABLE).isPresent(),
                "the table itself remains (the JPA entity still maps to it)");
    }

    @Test
    void lineagePersistsAcrossRestartWithoutRescanning(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        PipelineResult scanned = CodeAtlasPipeline.withDiscoveredParsers().run(repo, config(index));

        // "Restart": a fresh store handle, no pipeline, lineage straight from disk.
        try (AtlasStore reopened = AtlasStore.atPath(index)) {
            SoftwareModel loaded = reopened.loadModel();
            LineageResult lineage = new LineageService().trace(loaded, LineageQuery.downstream(ENDPOINT));
            assertTrue(lineage.paths().stream().anyMatch(p -> p.nodeIds().contains(TABLE)),
                    "the endpoint→table path must be answerable from the persisted index alone");
            assertEquals(scanned.scanId(), reopened.latestCompletedScan().orElseThrow().scanKey());
        }
    }

    @Test
    void failedScanLeavesPreviousLineageReadable(@TempDir Path repo, @TempDir Path indexDir)
            throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        pipeline.run(repo, config(index));

        assertThrows(IllegalArgumentException.class,
                () -> pipeline.run(repo.resolve("no-such-dir"), config(index)));

        try (AtlasStore reopened = AtlasStore.atPath(index)) {
            SoftwareModel loaded = reopened.loadModel();
            LineageResult lineage = new LineageService().trace(loaded, LineageQuery.downstream(ENDPOINT));
            assertTrue(lineage.paths().stream().anyMatch(p -> p.nodeIds().contains(TABLE)),
                    "a failed scan must not damage the last completed lineage graph");
        }
    }
}
