package com.codeatlas.core;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.analysis.lineage.LineageService;
import com.codeatlas.model.Entity;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.reporting.LineageJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the complete Java data-lineage vertical slice on the customer fixture:
 * endpoint → controller → service → transformation → repository write → table,
 * with evidence on every edge and honest gaps for what could not be resolved.
 */
class JavaLineageEndToEndTest {

    private static final String ENDPOINT = "java:endpoint:POST:/customers";
    private static final String TABLE = "sql:table:customer";
    private static final String SERVICE_CREATE =
            "java:method:com.example.customer.CustomerService#createCustomer(CustomerRequest)";

    private PipelineResult run(Path repo) {
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());
    }

    @Test
    void downstreamPathFromEndpointReachesTheCustomerTable(@TempDir Path repo) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        PipelineResult result = run(repo);
        SoftwareModel model = result.model();

        // The endpoint and table exist with the documented stable ids.
        assertTrue(model.entity(ENDPOINT).isPresent(), "endpoint entity missing");
        assertTrue(model.entity(TABLE).isPresent(), "table entity missing");

        LineageResult lineage = new LineageService().trace(model, LineageQuery.downstream(ENDPOINT));
        LineageResult.Path storePath = lineage.paths().stream()
                .filter(p -> p.nodeIds().contains(TABLE))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no path from endpoint to table; paths=" + lineage.paths()));

        // Ordered chain: endpoint → controller handler → service method → table.
        List<String> nodes = storePath.nodeIds();
        assertEquals(ENDPOINT, nodes.get(0));
        assertTrue(nodes.contains(
                        "java:method:com.example.customer.CustomerController#createCustomer(CustomerRequest)"),
                "controller handler missing from path: " + nodes);
        assertTrue(nodes.contains(SERVICE_CREATE), "service method missing from path: " + nodes);
        assertTrue(nodes.indexOf(SERVICE_CREATE) < nodes.indexOf(TABLE), "service must precede table");

        // Every edge carries evidence: rule id, confidence, resolution status.
        for (LineageResult.Edge edge : storePath.edges()) {
            assertFalse(edge.ruleId().isBlank(), "edge lacks rule id: " + edge);
            assertTrue(edge.confidence() > 0 && edge.confidence() <= 1.0, "bad confidence: " + edge);
            assertFalse(edge.status().isBlank(), "edge lacks resolution status: " + edge);
        }
        assertTrue(storePath.minConfidence() >= 0.85, "resolved chain should be high confidence");
    }

    @Test
    void transformationValidationAndReadWriteSemanticsAreDetected(@TempDir Path repo) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        SoftwareModel model = run(repo).model();

        String mapperToEntity = "java:method:com.example.customer.CustomerMapper#toEntity(CustomerRequest)";
        Entity mapper = model.entity(mapperToEntity).orElseThrow();
        assertTrue(mapper.boolAttribute(Entity.Attributes.TRANSFORMATION, false),
                "toEntity must be tagged a transformation");
        assertTrue(edgeExists(model, RelationshipKind.CONSUMES, mapperToEntity,
                "java:type:com.example.customer.CustomerRequest"));
        assertTrue(edgeExists(model, RelationshipKind.PRODUCES, mapperToEntity,
                "java:type:com.example.customer.CustomerEntity"));

        // Request DTO is validated by the explicit validator call.
        assertTrue(edgeExists(model, RelationshipKind.VALIDATED_BY,
                        "java:type:com.example.customer.CustomerRequest",
                        "java:method:com.example.customer.CustomerValidator#validate(CustomerRequest)"),
                "CustomerRequest must be validated_by CustomerValidator.validate");

        // save() writes, findById() reads — both against the same table.
        assertTrue(edgeExists(model, RelationshipKind.WRITES_TO, SERVICE_CREATE, TABLE),
                "createCustomer must write to the customer table");
        assertTrue(edgeExists(model, RelationshipKind.READS_FROM,
                        "java:method:com.example.customer.CustomerService#getCustomer(Long)", TABLE),
                "getCustomer must read from the customer table");

        // Entity→table mapping is explicit @Table: DISCOVERED-grade confidence 1.00.
        Relationship mapsTo = model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.MAPS_TO
                        && r.fromId().equals("java:type:com.example.customer.CustomerEntity"))
                .findFirst().orElseThrow();
        assertEquals(TABLE, mapsTo.toId());
        assertEquals("1.00", mapsTo.attributes().get(EvidenceKeys.CONFIDENCE));
        assertEquals("ATLAS-LINEAGE-JPA-TABLE-001", mapsTo.attributes().get(EvidenceKeys.RULE_ID));

        // Repository manages the entity and persists to its table.
        assertTrue(edgeExists(model, RelationshipKind.MANAGES,
                "java:type:com.example.customer.CustomerRepository",
                "java:type:com.example.customer.CustomerEntity"));
        assertTrue(edgeExists(model, RelationshipKind.PERSISTS_TO,
                "java:type:com.example.customer.CustomerRepository", TABLE));
    }

    @Test
    void ambiguityAndExternalCallsSurfaceAsHonestGapsNotFacts(@TempDir Path repo) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        SoftwareModel model = run(repo).model();

        // Two NotificationSender implementations: both kept, both ambiguous+inferred.
        List<Relationship> sendEdges = model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.INVOKES
                        && r.fromId().equals(SERVICE_CREATE)
                        && r.toId().contains("NotificationSender#send")
                        || (r.kind() == RelationshipKind.INVOKES
                        && r.fromId().equals(SERVICE_CREATE)
                        && (r.toId().contains("EmailNotificationSender") || r.toId().contains("SmsNotificationSender"))))
                .toList();
        assertEquals(2, sendEdges.size(), "both implementations must be kept: " + sendEdges);
        for (Relationship edge : sendEdges) {
            assertEquals(ResolutionStatus.INFERRED, edge.status());
            assertEquals("true", edge.attributes().get(EvidenceKeys.AMBIGUOUS));
            assertEquals("0.50", edge.attributes().get(EvidenceKeys.CONFIDENCE));
        }

        // The external AnalyticsClient call is an explicit UNRESOLVED edge, not dropped.
        assertTrue(model.relationships().stream().anyMatch(r ->
                        r.kind() == RelationshipKind.INVOKES
                                && r.status() == ResolutionStatus.UNRESOLVED
                                && r.fromId().equals(SERVICE_CREATE)
                                && r.toId().contains("AnalyticsClient")),
                "external client call must remain as an unresolved edge");

        // Traversal reports the gaps: unresolved target, ambiguity, external consumer.
        LineageResult lineage = new LineageService().trace(model, LineageQuery.downstream(ENDPOINT));
        assertTrue(lineage.gaps().stream().anyMatch(g -> g.kind().equals(LineageResult.Gap.UNRESOLVED_TARGET)
                && g.description().contains("AnalyticsClient")), "gaps=" + lineage.gaps());
        assertTrue(lineage.gaps().stream().anyMatch(g ->
                g.kind().equals(LineageResult.Gap.AMBIGUOUS_IMPLEMENTATION)), "gaps=" + lineage.gaps());
        assertTrue(lineage.gaps().stream().anyMatch(g ->
                        g.kind().equals(LineageResult.Gap.EXTERNAL_CONSUMER)
                                && g.description().contains("CustomerResponse")),
                "response consumer must be an explicit external gap: " + lineage.gaps());

        // Excluding inferred edges removes the ambiguous branches from traversal.
        LineageResult strict = new LineageService().trace(model,
                new LineageQuery(ENDPOINT, LineageQuery.Direction.DOWNSTREAM, 8, false, 0.40));
        assertTrue(strict.paths().stream().noneMatch(p ->
                        p.nodeIds().stream().anyMatch(n -> n.contains("NotificationSender")
                                || n.contains("EmailNotification") || n.contains("SmsNotification"))),
                "inferred/ambiguous edges must be excludable");
    }

    @Test
    void upstreamFromTheTableReachesTheEndpoint(@TempDir Path repo) throws IOException {
        LineageFixtures.writeCustomerApp(repo);
        SoftwareModel model = run(repo).model();

        LineageResult upstream = new LineageService().trace(model, LineageQuery.upstream(TABLE));
        assertTrue(upstream.paths().stream().anyMatch(p -> p.nodeIds().contains(ENDPOINT)),
                "upstream from the table must reach the POST endpoint; paths=" + upstream.paths());
    }

    @Test
    void lineageJsonIsDeterministicAcrossIndependentRuns(@TempDir Path repoA, @TempDir Path repoB)
            throws IOException {
        LineageFixtures.writeCustomerApp(repoA);
        LineageFixtures.writeCustomerApp(repoB);

        String jsonA = lineageJson(run(repoA));
        String jsonB = lineageJson(run(repoB));
        assertEquals(jsonA, jsonB, "identical content must yield byte-identical lineage JSON");
    }

    private String lineageJson(PipelineResult result) {
        LineageQuery query = LineageQuery.downstream(ENDPOINT);
        LineageResult lineage = new LineageService().trace(result.model(), query);
        return new LineageJsonWriter().render(result.scanId(), query, lineage);
    }

    private static boolean edgeExists(SoftwareModel model, RelationshipKind kind, String fromId, String toId) {
        return model.relationships().stream().anyMatch(r ->
                r.kind() == kind && r.fromId().equals(fromId) && r.toId().equals(toId));
    }
}
