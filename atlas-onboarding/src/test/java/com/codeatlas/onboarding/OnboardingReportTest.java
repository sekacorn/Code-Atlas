package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.tools.AtlasToolApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Report rendering: deterministic JSON, self-contained HTML, files written outside the repo. */
class OnboardingReportTest {

    @TempDir
    static Path work;
    static AtlasToolApi api;
    static OnboardingResult result;

    @BeforeAll
    static void scanOnce() throws IOException {
        Path repo = work.resolve("mission");
        MissionSystemFixture.write(repo);
        var scanned = OnboardingTestSupport.scan(repo, work.resolve("index").resolve("atlas"));
        api = scanned.api();
        result = new OnboardingService(api,
                OnboardingOptions.forRepository("mission", "Code Atlas test"), scanned.coverage()).run();
    }

    @AfterAll
    static void close() {
        if (api != null) {
            api.close();
        }
    }

    @Test
    void jsonIsDeterministicAndOmitsVolatileDurations() {
        String a = OnboardingReport.toJson(result);
        String b = OnboardingReport.toJson(result);
        assertEquals(a, b);
        assertFalse(a.contains("duration"), "durations are volatile and must not appear in the JSON body");
        assertFalse(a.contains("generatedAt"), "no wall-clock timestamp in the deterministic body");
    }

    @Test
    void htmlIsSelfContainedWithNoExternalAssets() {
        String html = OnboardingReport.toHtml(result, "2026-07-15T00:00:00Z");
        assertFalse(html.contains("<script"), "no scripts");
        assertFalse(html.contains("http://"), "no external http assets");
        assertFalse(html.contains("https://"), "no external https assets");
        assertTrue(html.contains("<style"), "styles are inlined");
    }

    @Test
    void htmlContainsEveryMajorSection() {
        String html = OnboardingReport.toHtml(result, null);
        for (String section : List.of("Repository", "Scan health", "System inventory", "Entry points",
                "Architecture orientation", "Java/Ada boundaries", "Representative data-lineage paths",
                "Central components", "Risks and gaps", "Suggested reading order",
                "Questions for subject-matter experts", "Final onboarding summary", "Known limitations")) {
            assertTrue(html.contains(section), "HTML is missing section: " + section);
        }
    }

    @Test
    void writerProducesThreeFilesOutsideTheRepository(@TempDir Path out) {
        List<Path> written = new OnboardingReportWriter().writeAll(result, out.resolve("reports"), "now");
        assertEquals(3, written.size());
        assertTrue(Files.exists(out.resolve("reports").resolve("onboarding-report.json")));
        assertTrue(Files.exists(out.resolve("reports").resolve("onboarding-report.html")));
        assertTrue(Files.exists(out.resolve("reports").resolve("onboarding-report.txt")));
    }

    @Test
    void writerRefusesToWriteInsideTheAnalyzedRepository() {
        Path repo = work.resolve("mission");
        assertThrows(IllegalArgumentException.class,
                () -> OnboardingReportWriter.ensureOutsideRepository(repo.resolve("reports"), repo));
        assertThrows(IllegalArgumentException.class,
                () -> OnboardingReportWriter.ensureOutsideRepository(repo, repo));
    }

    @Test
    void insideRepositoryPredicateDistinguishesNestedFromSiblingPaths() {
        Path repo = work.resolve("mission");
        assertTrue(OnboardingReportWriter.isInsideRepository(repo, repo), "the repo itself is inside");
        assertTrue(OnboardingReportWriter.isInsideRepository(repo.resolve("a/b"), repo), "nested is inside");
        assertFalse(OnboardingReportWriter.isInsideRepository(work.resolve("out"), repo),
                "a sibling directory is outside");
        // A sibling whose name merely starts with the repo name is NOT inside it.
        assertFalse(OnboardingReportWriter.isInsideRepository(work.resolve("mission-report"), repo),
                "a name-prefixed sibling is outside");
    }
}
