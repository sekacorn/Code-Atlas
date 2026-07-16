package com.codeatlas.onboarding;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.BoundaryType;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.ExpertQuestion;
import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.onboarding.model.ReadingRecommendation;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.tools.AtlasToolApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end onboarding over the mission-system fixture. Scans the fixture once
 * into a temporary file-backed index, then queries it read-only exactly as the
 * production {@code atlas onboard} command does. Every assertion checks a stated
 * completion criterion for the guided-onboarding milestone.
 */
class OnboardingServiceTest {

    @TempDir
    static Path work;

    static AtlasToolApi api;
    static OnboardingResult result;
    static AnalysisCoverage coverage;
    static OnboardingOptions options;

    @BeforeAll
    static void scanOnce() throws IOException {
        Path repo = work.resolve("mission");
        MissionSystemFixture.write(repo);
        var scanned = OnboardingTestSupport.scan(repo, work.resolve("index").resolve("atlas"));
        api = scanned.api();
        coverage = scanned.coverage();
        options = OnboardingOptions.forRepository("mission", "Code Atlas test");
        result = new OnboardingService(api, options, coverage).run();
    }

    @AfterAll
    static void close() {
        if (api != null) {
            api.close();
        }
    }

    // ---- workflow ----

    @Test
    void allTwelveStagesRunInOrder() {
        List<String> names = result.stages().stream().map(s -> s.name()).toList();
        assertEquals(List.of("Repository Intake", "Scan Health", "System Inventory",
                "Entry-Point Discovery", "Architecture Orientation", "Java/Ada Boundary Discovery",
                "Data-Lineage Sampling", "Central Component Review", "Risk and Gap Review",
                "Suggested Reading Order", "Questions for Subject-Matter Experts",
                "Final Onboarding Summary"), names);
    }

    @Test
    void noStageFailedOnAHealthyFixture() {
        assertTrue(result.stages().stream().noneMatch(s ->
                        s.completeness() == com.codeatlas.onboarding.model.Completeness.FAILED),
                "no stage should fail on a well-formed repository");
    }

    @Test
    void usesTheLatestCompletedScan() {
        assertTrue(result.scanId().startsWith("scan-"));
        assertEquals(api.scanId(), result.scanId());
    }

    // ---- entry points ----

    @Test
    void javaMainIsIdentified() {
        assertTrue(result.entryPoints().stream().anyMatch(e -> e.type().equals("Java main method")
                && e.stableId().equals("java:method:com.example.mission.MissionApplication#main(String[])")));
    }

    @Test
    void springEndpointIsIdentified() {
        assertTrue(result.entryPoints().stream().anyMatch(e -> e.type().startsWith("REST endpoint")
                && e.stableId().equals("java:endpoint:POST:/missions")));
    }

    @Test
    void adaMainDeclaredByTheGnatProjectIsIdentifiedAsDiscoveredNotInferred() {
        EntryPointSummary main = result.entryPoints().stream()
                .filter(e -> e.stableId().equals("ada:procedure:Mission_Main")).findFirst()
                .orElseThrow(() -> new AssertionError("the Ada main was not identified"));
        assertEquals("ada", main.language());
        assertEquals("Build-declared main (gnat)", main.type(),
                "a build file declaring the main outranks the shape heuristic");
        assertEquals("DISCOVERED", main.resolutionStatus(), "a declared main is a fact, not an inference");
        assertTrue(main.confidence().contains("mission.gpr"), main.confidence());
        // It must be reported once, not twice (declared and inferred).
        assertEquals(1, result.entryPoints().stream()
                .filter(e -> e.stableId().equals("ada:procedure:Mission_Main")).count());
    }

    @Test
    void buildModulesAreInventoriedAndDrivePlatformFacts() {
        assertTrue(result.inventory().categories().stream()
                        .anyMatch(c -> c.name().equals("Build modules") && c.count() == 2),
                "the Maven module and the GNAT project are both inventoried");
        assertEquals(List.of("GNAT project (GPRbuild)", "Maven"), result.intake().buildSystems(),
                "build systems come from the modules that were actually parsed");
    }

    @Test
    void ordinaryHelperMethodsAreNotEntryPoints() {
        assertTrue(result.entryPoints().stream().noneMatch(e -> e.stableId().contains("MissionRunner#helper")
                || e.stableId().contains("MissionRunner#run")));
    }

    // ---- boundaries ----

    @Test
    void nativeJniBoundaryConnectsToTheAdaSubprogram() {
        BoundarySummary direct = boundaryOf(BoundaryType.DIRECT_BOUNDARY);
        assertTrue(direct.javaSideId().contains("AdaMissionAdapter#calculateRoute"));
        assertEquals("ada:procedure:Mission_Planning.Calculate_Route", direct.adaSideId());
        assertEquals("INFERRED", direct.resolutionStatus(), "the Ada counterpart is name-matched, not resolved");
        assertFalse(direct.evidence().isEmpty(), "a boundary must cite evidence");
    }

    @Test
    void processAndMessageBoundariesAreDetectedFromEvidence() {
        assertTrue(result.boundaries().stream().anyMatch(b -> b.type() == BoundaryType.PROCESS_BOUNDARY));
        assertTrue(result.boundaries().stream().anyMatch(b -> b.type() == BoundaryType.MESSAGE_BOUNDARY));
    }

    @Test
    void unresolvedBoundariesRemainVisible() {
        assertTrue(result.boundaries().stream().anyMatch(b -> b.resolutionStatus().equals("UNRESOLVED")
                        || b.adaSideId().isEmpty()),
                "boundaries whose far side is outside the sources are still reported");
    }

    @Test
    void everyBoundaryHasEvidenceAndMissingInformation() {
        assertFalse(result.boundaries().isEmpty());
        result.boundaries().forEach(b -> {
            assertFalse(b.evidence().isEmpty(), "boundary must cite evidence: " + b);
            assertFalse(b.missingInformation().isBlank(), "boundary must state missing info: " + b);
        });
    }

    // ---- lineage sampling ----

    @Test
    void lineageSampleRespectsMaxAndIncludesJavaAndAda() {
        assertTrue(result.lineagePaths().size() <= options.maxPathsOrDefault());
        assertTrue(result.lineagePaths().stream().anyMatch(p -> p.startId().startsWith("java:")),
                "a Java-origin path is included");
        assertTrue(result.lineagePaths().stream().anyMatch(p -> p.startId().startsWith("ada:")),
                "an Ada-origin path is included");
    }

    @Test
    void highConfidencePathsArePreferredAndPartialsLabelled() {
        assertTrue(result.lineagePaths().stream().anyMatch(p -> p.confidence().startsWith("High")),
                "at least one high-confidence path is sampled");
        result.lineagePaths().forEach(p -> {
            if (!p.unresolvedGaps().isEmpty()) {
                assertTrue(p.partial(), "a path with unresolved gaps must be flagged partial");
            }
        });
    }

    @Test
    void lineageSelectionIsDeterministic() {
        OnboardingResult again = new OnboardingService(api, options, coverage).run();
        assertEquals(OnboardingReport.toJson(result), OnboardingReport.toJson(again),
                "identical scan + options must yield identical JSON");
    }

    // ---- central components ----

    @Test
    void centralComponentsAreRankedDeterministicallyByMultipleSignals() {
        List<CentralComponentSummary> cs = result.centralComponents();
        assertFalse(cs.isEmpty());
        // Ranking is score-descending, ties broken by stable id.
        for (int i = 1; i < cs.size(); i++) {
            CentralComponentSummary a = cs.get(i - 1);
            CentralComponentSummary b = cs.get(i);
            assertTrue(a.score() > b.score()
                            || (a.score() == b.score() && a.stableId().compareTo(b.stableId()) <= 0),
                    "components must be ordered by score then stable id");
        }
        // Multiple signals contribute: fan-in and entry-point hosting both appear.
        assertTrue(cs.stream().anyMatch(c -> c.scoreBasis().contains("fan-in")));
        assertTrue(cs.stream().anyMatch(c -> c.scoreBasis().contains("hosts an entry point")));
        assertTrue(cs.stream().anyMatch(c -> c.scoreBasis().contains("data store")));
    }

    // ---- reading order ----

    @Test
    void readingOrderStartsWithBuildFilesAndHasNoDuplicates() {
        List<ReadingRecommendation> order = result.readingOrder();
        assertFalse(order.isEmpty());
        assertTrue(order.get(0).reason().startsWith("Build/project"),
                "build/project files come first");
        long distinct = order.stream().map(ReadingRecommendation::targetId).distinct().count();
        assertEquals(order.size(), distinct, "no component is recommended twice");
        order.forEach(r -> assertFalse(r.reason().isBlank(), "every recommendation states why it matters"));
    }

    @Test
    void readingOrderPlacesEntryPointsBeforeCoreAdaPackagesAndIncludesTheBoundary() {
        List<ReadingRecommendation> order = result.readingOrder();
        int entry = indexOfContains(order, "MissionApplication#main");
        int adaPkg = indexOfContains(order, "ada:package:Mission_Planning");
        assertTrue(entry >= 0 && adaPkg >= 0 && entry < adaPkg,
                "entry points are read before the deep Ada packages");
        BoundarySummary direct = boundaryOf(BoundaryType.DIRECT_BOUNDARY);
        assertTrue(order.stream().anyMatch(r -> r.targetId().equals(direct.javaSideId())),
                "the boundary component is in the reading order");
    }

    // ---- expert questions ----

    @Test
    void expertQuestionsReferenceRealComponentsAndCiteEvidence() {
        assertFalse(result.expertQuestions().isEmpty());
        result.expertQuestions().forEach(q -> {
            assertFalse(q.componentIds().isEmpty(), "a question must reference real components: " + q.role());
            assertFalse(q.evidence().isEmpty(), "a question must cite evidence: " + q.role());
            q.componentIds().forEach(id -> assertTrue(
                    id.startsWith("java:") || id.startsWith("ada:") || id.startsWith("sql:"),
                    "component id must be a real stable id: " + id));
        });
    }

    @Test
    void expertQuestionsOnlyForSupportedRolesAndPhraseResolvedFactsAsFacts() {
        List<String> roles = result.expertQuestions().stream().map(ExpertQuestion::role).toList();
        // Ada specialist present because the fixture has Ada; database engineer because a table exists.
        assertTrue(roles.contains("Ada specialist"));
        assertTrue(roles.contains("Database engineer"));
        // The Java-specialist question confirms a resolved fact (centrality), not uncertainty.
        result.expertQuestions().stream().filter(q -> q.role().equals("Java specialist")).findFirst()
                .ifPresent(q -> assertEquals(ExpertQuestion.BASIS_CONFIRMED, q.basis()));
        // The technical-lead boundary question asks a specific, grounded, unresolved question.
        result.expertQuestions().stream().filter(q -> q.role().equals("Technical lead")).findFirst()
                .ifPresent(q -> {
                    assertEquals(ExpertQuestion.BASIS_UNRESOLVED, q.basis());
                    assertTrue(q.question().contains("Calculate_Route") || q.question().contains("Adapter"));
                });
    }

    // ---- final summary ----

    @Test
    void finalSummaryAnswersAllQuestionsAndSeparatesFactsFromInferences() {
        var summary = result.summary();
        assertEquals(10, summary.answers().size());
        assertFalse(summary.confirmedFacts().isEmpty());
        assertFalse(summary.inferredArchitecture().isEmpty());
        assertTrue(summary.answers().stream()
                .anyMatch(qa -> qa.question().contains("How do Java and Ada interact?")
                        && qa.answer().contains("boundary")));
    }

    @Test
    void reportStatesNonCertificationHonestly() {
        assertTrue(result.knownLimitations().stream().anyMatch(x ->
                x.toLowerCase().contains("not operational authorization")
                        || x.toLowerCase().contains("certification")));
    }

    // ---- helpers ----

    private BoundarySummary boundaryOf(BoundaryType type) {
        return result.boundaries().stream().filter(b -> b.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("expected a " + type + " boundary"));
    }

    private static int indexOfContains(List<ReadingRecommendation> order, String needle) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).targetId().contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
