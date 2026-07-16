package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.tools.AtlasToolApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Onboarding edge cases: honest degradation, conservative boundaries, and read-only guarantees. */
class OnboardingEdgeCasesTest {

    private OnboardingResult onboard(Path repo, Path index) throws IOException {
        var scanned = OnboardingTestSupport.scan(repo, index);
        try (AtlasToolApi api = scanned.api()) {
            return new OnboardingService(api,
                    OnboardingOptions.forRepository(repo.getFileName().toString(), "test"),
                    scanned.coverage()).run();
        }
    }

    @Test
    void nameSimilarityAloneDoesNotCreateABoundary(@TempDir Path work) throws IOException {
        // A Java class and an Ada package share the name "Router" with no crossing
        // edge (no native method, no process/message call, no shared store). No
        // boundary must be produced from the coincidental name alone.
        Path repo = work.resolve("repo");
        Files.createDirectories(repo.resolve("java"));
        Files.createDirectories(repo.resolve("ada"));
        Files.writeString(repo.resolve("java/Router.java"),
                "public class Router { void route() {} }");
        Files.writeString(repo.resolve("ada/router.ads"),
                "package Router is\n   procedure Start;\nend Router;\n");
        OnboardingResult result = onboard(repo, work.resolve("index").resolve("atlas"));
        assertTrue(result.boundaries().isEmpty(),
                "two same-named entities with no crossing evidence must not form a boundary");
    }

    @Test
    void withoutABuildFileTheAdaMainFallsBackToTheInferredShapeHeuristic(@TempDir Path work)
            throws IOException {
        // No .gpr declares an entry point here, so the top-level parameterless
        // procedure is still surfaced — but honestly labelled as an inference.
        Path repo = work.resolve("repo");
        Files.createDirectories(repo.resolve("ada"));
        Files.writeString(repo.resolve("ada/mission_main.adb"),
                "procedure Mission_Main is\nbegin\n   null;\nend Mission_Main;\n");
        OnboardingResult result = onboard(repo, work.resolve("index").resolve("atlas"));

        var main = result.entryPoints().stream()
                .filter(e -> e.stableId().equals("ada:procedure:Mission_Main")).findFirst()
                .orElseThrow(() -> new AssertionError("the Ada main was not identified"));
        assertTrue(main.type().startsWith("Ada main"), main.type());
        assertEquals("INFERRED", main.resolutionStatus(),
                "without a build declaration the main is an inference, and says so");
    }

    @Test
    void failedScanStillProducesAnHonestLimitedReport(@TempDir Path work) throws IOException {
        // A repository with only an unparseable file: the scan completes but nothing
        // is analyzed. Onboarding must still produce a full, honest report labelled FAILED.
        Path repo = work.resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("notes.txt"), "just some text, no code here\n");
        OnboardingResult result = onboard(repo, work.resolve("index").resolve("atlas"));
        assertEquals(ScanHealthSummary.FAILED, result.scanHealth().status());
        assertEquals("FAILED", result.status());
        assertEquals(12, result.stages().size(), "all stages still run and report honestly");
        assertFalse(result.knownLimitations().isEmpty());
    }

    @Test
    void onboardingNeverModifiesTheAnalyzedRepository(@TempDir Path work) throws IOException {
        Path repo = work.resolve("mission");
        MissionSystemFixture.write(repo);
        String before = OnboardingTestSupport.hashTree(repo);
        long filesBefore = OnboardingTestSupport.countFiles(repo);

        var scanned = OnboardingTestSupport.scan(repo, work.resolve("index").resolve("atlas"));
        Path reports = work.resolve("out");
        try (AtlasToolApi api = scanned.api()) {
            OnboardingResult result = new OnboardingService(api,
                    OnboardingOptions.forRepository("mission", "test"), scanned.coverage()).run();
            OnboardingReportWriter.ensureOutsideRepository(reports, repo);
            new OnboardingReportWriter().writeAll(result, reports, "now");
        }

        assertEquals(before, OnboardingTestSupport.hashTree(repo),
                "the analyzed repository's files must be byte-identical after onboarding");
        assertEquals(filesBefore, OnboardingTestSupport.countFiles(repo),
                "onboarding must not create files inside the analyzed repository");
        assertTrue(Files.exists(reports.resolve("onboarding-report.json")),
                "reports are written outside the repository");
    }
}
