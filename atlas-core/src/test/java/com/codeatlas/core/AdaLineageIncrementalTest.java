package com.codeatlas.core;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.analysis.lineage.LineageService;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.reporting.LineageJsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ada lineage under incremental scanning: reuse stays identical, edits update facts. */
class AdaLineageIncrementalTest {

    private static final String LOAD = "ada:procedure:Mission_Data.Load_Route";

    private static PipelineConfig config(Path index) {
        return PipelineConfig.builder().indexPath(index).build();
    }

    @Test
    void reusedAdaScanProducesIdenticalLineage(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();

        PipelineResult first = pipeline.run(repo, config(index));
        PipelineResult second = pipeline.run(repo, config(index));
        assertEquals(5, second.coverage().filesReused(), "all Ada fixture files must be reused");
        assertEquals(lineageJson(first), lineageJson(second),
                "reused Ada facts must yield byte-identical lineage JSON");
    }

    @Test
    void editingTheBodyUpdatesStateLineage(@TempDir Path repo, @TempDir Path indexDir) throws IOException {
        AdaLineageFixtures.writeMissionApp(repo);
        Path index = indexDir.resolve("atlas");
        CodeAtlasPipeline pipeline = CodeAtlasPipeline.withDiscoveredParsers();
        PipelineResult before = pipeline.run(repo, config(index));
        assertTrue(hasWrite(before, "ada:variable:Mission_Data.Status",
                "ada:procedure:Operations.Reset_Mission"));

        // Reset_Mission no longer touches Mission_Data.Status.
        Files.writeString(repo.resolve("src/operations.adb"), """
                with Mission_Data;

                package body Operations is

                   procedure Reset_Mission is
                   begin
                      null;
                   end Reset_Mission;

                end Operations;
                """);
        PipelineResult after = pipeline.run(repo, config(index));
        assertEquals(4, after.coverage().filesReused(), "only the edited body is re-parsed");
        assertFalse(hasWrite(after, "ada:variable:Mission_Data.Status",
                        "ada:procedure:Operations.Reset_Mission"),
                "the removed qualified write must disappear from lineage");
    }

    private static boolean hasWrite(PipelineResult result, String variableId, String writerId) {
        return result.model().relationships().stream().anyMatch(r ->
                r.kind() == RelationshipKind.WRITES_TO
                        && r.fromId().equals(writerId) && r.toId().equals(variableId));
    }

    private String lineageJson(PipelineResult result) {
        LineageQuery query = LineageQuery.downstream(LOAD);
        LineageResult lineage = new LineageService().trace(result.model(), query);
        return new LineageJsonWriter().render(result.scanId(), query, lineage);
    }
}
