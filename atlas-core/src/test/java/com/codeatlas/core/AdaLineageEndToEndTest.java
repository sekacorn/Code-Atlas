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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Ada data-lineage vertical slice on the mission fixture:
 * console input → Load_Route → Transform_Waypoints → package state →
 * Publish_Route → console output, with evidence on every edge and honest gaps.
 */
class AdaLineageEndToEndTest {

    private static final String LOAD = "ada:procedure:Mission_Data.Load_Route";
    private static final String PUBLISH = "ada:procedure:Mission_Data.Publish_Route";
    private static final String TRANSFORM =
            "ada:function:Mission_Data.Transform_Waypoints(Raw_Types.Raw_Data)";
    private static final String ROUTE_STATE = "ada:variable:Mission_Data.Current_Route";
    private static final String STATUS_STATE = "ada:variable:Mission_Data.Status";
    private static final String CONSOLE_IN = "ada:source:console_input";
    private static final String CONSOLE_OUT = "ada:sink:console_output";

    private PipelineResult run(Path repo) {
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());
    }

    @Test
    void packageStateIsExtractedWithSpecBodyEvidence(@TempDir Path repo) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        SoftwareModel model = run(repo).model();

        Entity route = model.entity(ROUTE_STATE).orElseThrow();
        assertTrue(route.boolAttribute(Entity.Attributes.HAS_BODY, false), "body-declared state");
        assertFalse(route.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false),
                "body state is not externally visible");
        assertEquals("Route_Type", route.attribute("variableType").orElseThrow());

        Entity status = model.entity(STATUS_STATE).orElseThrow();
        assertTrue(status.boolAttribute(Entity.Attributes.HAS_SPEC, false), "spec-declared state");
        assertTrue(status.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false));

        // Locals and record components must never become state entities.
        assertFalse(model.entity("ada:variable:Mission_Data.Result").isPresent(),
                "subprogram locals are not package state");
        assertFalse(model.entity("ada:variable:Mission_Data.Length").isPresent(),
                "record components are not package state");
    }

    @Test
    void stateReadsWritesIoAndTransformationCarryRuleEvidence(@TempDir Path repo) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        SoftwareModel model = run(repo).model();

        // Same-package (enclosing) write: 0.85 RESOLVED.
        Relationship write = edge(model, RelationshipKind.WRITES_TO, LOAD, ROUTE_STATE);
        assertEquals("ATLAS-LINEAGE-ADA-WRITE-001", write.attributes().get(EvidenceKeys.RULE_ID));
        assertEquals("0.85", write.attributes().get(EvidenceKeys.CONFIDENCE));
        assertEquals(ResolutionStatus.RESOLVED, write.status());
        assertTrue(write.location().isPresent(), "state write must carry source evidence");

        // Cross-package qualified write: 0.90.
        Relationship qualified = edge(model, RelationshipKind.WRITES_TO,
                "ada:procedure:Operations.Reset_Mission", STATUS_STATE);
        assertEquals("0.90", qualified.attributes().get(EvidenceKeys.CONFIDENCE));

        // Same-file state read from the publisher.
        Relationship read = edge(model, RelationshipKind.READS_FROM, PUBLISH, ROUTE_STATE);
        assertEquals("ATLAS-LINEAGE-ADA-READ-001", read.attributes().get(EvidenceKeys.RULE_ID));

        // Console I/O through Ada.Text_IO with-clause evidence.
        Relationship consoleRead = edge(model, RelationshipKind.READS_FROM, LOAD, CONSOLE_IN);
        assertEquals("ATLAS-LINEAGE-ADA-IO-001", consoleRead.attributes().get(EvidenceKeys.RULE_ID));
        Relationship consoleWrite = edge(model, RelationshipKind.WRITES_TO, PUBLISH, CONSOLE_OUT);
        assertEquals("ATLAS-LINEAGE-ADA-IO-002", consoleWrite.attributes().get(EvidenceKeys.RULE_ID));

        // The transformation function consumes Raw_Data and produces Route_Type.
        Relationship consumes = edge(model, RelationshipKind.CONSUMES, TRANSFORM,
                "ada:type:Raw_Types.Raw_Data");
        Relationship produces = edge(model, RelationshipKind.PRODUCES, TRANSFORM,
                "ada:type:Mission_Data.Route_Type");
        assertEquals("ATLAS-LINEAGE-ADA-MAP-001", consumes.attributes().get(EvidenceKeys.RULE_ID));
        assertEquals("0.85", produces.attributes().get(EvidenceKeys.CONFIDENCE));
        assertTrue(model.entity(TRANSFORM).orElseThrow()
                .boolAttribute(Entity.Attributes.TRANSFORMATION, false));

        // Qualified cross-package call resolves at 0.95.
        Relationship parse = edge(model, RelationshipKind.INVOKES, LOAD,
                "ada:function:Raw_Types.Parse(String)");
        assertEquals("0.95", parse.attributes().get(EvidenceKeys.CONFIDENCE));
    }

    @Test
    void downstreamReachesStateAndGapsStayVisible(@TempDir Path repo) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        PipelineResult result = run(repo);
        SoftwareModel model = result.model();

        LineageResult lineage = new LineageService().trace(model, LineageQuery.downstream(LOAD));
        assertTrue(lineage.paths().stream().anyMatch(p -> p.nodeIds().contains(ROUTE_STATE)),
                "Load_Route must reach the package state; paths=" + lineage.paths());
        assertTrue(lineage.paths().stream().anyMatch(p -> p.nodeIds().contains(CONSOLE_IN)),
                "the console source must appear in the downstream branches");
        assertTrue(lineage.paths().stream().anyMatch(p ->
                        p.nodeIds().contains("ada:type:Mission_Data.Route_Type")),
                "the transformation output type must be on a path");

        // The absent Telemetry package is an explicit unresolved gap, not silence.
        LineageResult publish = new LineageService().trace(model, LineageQuery.downstream(PUBLISH));
        assertTrue(publish.gaps().stream().anyMatch(g ->
                        g.kind().equals(LineageResult.Gap.UNRESOLVED_TARGET)
                                && g.description().contains("Telemetry")),
                "gaps=" + publish.gaps());

        // Consumed parser candidates must never surface as false gaps.
        assertTrue(lineage.gaps().stream().noneMatch(g ->
                        g.description().contains("Ada.Text_IO")
                                || g.description().contains("'Current_Route'")
                                || g.description().contains("'Status'")),
                "raw state candidates leaked into gaps: " + lineage.gaps());

        // Upstream from the state variable shows its writer and its reader.
        LineageResult upstream = new LineageService().trace(model, LineageQuery.upstream(ROUTE_STATE));
        assertTrue(upstream.paths().stream().anyMatch(p -> p.nodeIds().contains(LOAD)),
                "writer must be upstream of the state");
        assertTrue(upstream.paths().stream().anyMatch(p -> p.nodeIds().contains(PUBLISH)),
                "reader must be upstream of the state");

        // The report summary lists the console source and sink with a source trace.
        var summary = result.analysis().lineage();
        assertTrue(summary.sources().stream().anyMatch(v -> v.stableId().equals(CONSOLE_IN)));
        assertTrue(summary.sources().stream().anyMatch(v -> v.stableId().equals(CONSOLE_OUT)));
        assertTrue(summary.sourceTraces().stream().anyMatch(t ->
                        t.endpointId().equals(CONSOLE_IN) && t.reachesStore()),
                "the console-input trace must reach state/sink; traces=" + summary.sourceTraces());
    }

    @Test
    void adaLineageJsonIsDeterministicAcrossIndependentRuns(@TempDir Path repoA, @TempDir Path repoB)
            throws IOException {
        AdaLineageFixtures.writeMissionApp(repoA);
        AdaLineageFixtures.writeMissionApp(repoB);
        assertEquals(lineageJson(run(repoA)), lineageJson(run(repoB)),
                "identical Ada content must yield byte-identical lineage JSON");
    }

    private String lineageJson(PipelineResult result) {
        LineageQuery query = LineageQuery.downstream(LOAD);
        LineageResult lineage = new LineageService().trace(result.model(), query);
        return new LineageJsonWriter().render(result.scanId(), query, lineage);
    }

    private static Relationship edge(SoftwareModel model, RelationshipKind kind, String fromId, String toId) {
        return model.relationships().stream()
                .filter(r -> r.kind() == kind && r.fromId().equals(fromId) && r.toId().equals(toId))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "missing edge " + fromId + " -" + kind + "-> " + toId));
    }
}
