package com.codeatlas.onboarding;

import com.codeatlas.agents.AgentReport;
import com.codeatlas.agents.OrientationAgent;
import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.onboarding.model.ArchitectureOrientation;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.Completeness;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.ExpertQuestion;
import com.codeatlas.onboarding.model.FinalSummary;
import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.onboarding.model.OnboardingRisk;
import com.codeatlas.onboarding.model.OnboardingStageResult;
import com.codeatlas.onboarding.model.ReadingRecommendation;
import com.codeatlas.onboarding.model.RepositoryIntake;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.onboarding.model.SystemInventory;
import com.codeatlas.tools.AtlasToolApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The Repository Onboarding Coordinator: it runs the twelve deterministic stages in
 * order over a read-only index, isolates each stage so a single failure never
 * aborts the workflow, and assembles the evidence-backed onboarding package.
 *
 * <p>It creates no new facts of its own - it organizes the results of the existing
 * read-only tool API and the deterministic agents (Repository Orientation, entity
 * summaries) into one guided sequence. No LLM, no network, and it never touches the
 * analyzed repository (it is constructed from an already-open, read-only API).
 *
 * <p>Determinism: given the same scan and options, every field of the result is
 * byte-for-byte reproducible; only per-stage durations vary and are excluded from
 * the deterministic report body.
 */
public final class OnboardingService {

    static final String WORKFLOW_VERSION = "onboarding-workflow/1.0.0";

    private static final List<String> KNOWN_LIMITATIONS = List.of(
            "Onboarding provides evidence-backed software understanding, not operational authorization; "
                    + "it makes no certification, accreditation or completeness claim.",
            "Representative lineage paths are a small deterministic sample, not the whole system.",
            "Java<->Ada counterparts reached by name match are inferred; Code Atlas does not resolve "
                    + "symbols across languages.",
            "Entry points from schedulers, message listeners or build configuration are not detected.",
            "Reflection, dynamic dispatch, dynamic SQL, dependency-injection wiring and external "
                    + "configuration are blind spots that may add paths not shown.",
            "Entity scanning caps at " + OnboardingText.CANDIDATE_CAP + " per kind.");

    private final AtlasToolApi api;
    private final OnboardingOptions options;
    private final AnalysisCoverage exactCoverage; // present only when onboarding ran the scan itself

    public OnboardingService(AtlasToolApi api, OnboardingOptions options, AnalysisCoverage exactCoverage) {
        this.api = api;
        this.options = options;
        this.exactCoverage = exactCoverage;
    }

    public OnboardingResult run() {
        List<OnboardingStageResult> stages = new ArrayList<>();
        String scan = api.scanId();
        List<String> scanInput = List.of("scan " + scan);

        RepositoryIntake intake = stage(stages, "Repository Intake", scanInput, fallbackIntake(scan), () -> {
            RepositoryIntake i = new RepositoryIntakeBuilder(api, options).build();
            return produced(i, Completeness.COMPLETE,
                    i.displayName() + " - " + String.join(", ", i.languages()) + "; "
                            + i.totalFiles() + " files; schema v" + i.schemaVersion(),
                    List.of(EvidenceRef.of(scan)), warn(i.languages().isEmpty(), "no languages detected"));
        });

        ScanHealthSummary health = stage(stages, "Scan Health", scanInput, fallbackHealth(), () -> {
            ScanHealthSummary h = new ScanHealthAssessor(api).assess(exactCoverage);
            Completeness comp = h.status().equals(ScanHealthSummary.FAILED) ? Completeness.UNAVAILABLE
                    : h.exactFileCounts() ? Completeness.COMPLETE : Completeness.PARTIAL;
            return produced(h, comp, "Scan health: " + h.status() + " (" + h.resolutionRatePercent()
                    + "% references resolved)", List.of(EvidenceRef.of(scan)), h.reasons());
        });

        SystemInventory inventory = stage(stages, "System Inventory", scanInput,
                new SystemInventory(List.of()), () -> {
                    SystemInventory inv = new SystemInventoryBuilder(api).build();
                    int total = inv.categories().stream().mapToInt(SystemInventory.Category::count).sum();
                    return produced(inv, Completeness.COMPLETE,
                            inv.categories().size() + " categories, " + total + " artifacts inventoried",
                            List.of(EvidenceRef.of(scan)), List.of());
                });

        List<EntryPointSummary> entryPoints = stage(stages, "Entry-Point Discovery", scanInput,
                List.of(), () -> {
                    List<EntryPointSummary> eps = new EntryPointFinder(api).find();
                    long java = eps.stream().filter(e -> e.language().equals("java")).count();
                    long ada = eps.stream().filter(e -> e.language().equals("ada")).count();
                    return produced(eps, eps.isEmpty() ? Completeness.PARTIAL : Completeness.COMPLETE,
                            eps.size() + " entry point(s): " + java + " Java, " + ada + " Ada",
                            eps.stream().limit(6).flatMap(e -> e.evidence().stream()).toList(),
                            warn(eps.isEmpty(), "no entry points detected within analyzed evidence"));
                });

        ArchitectureOrientation orientation = stage(stages, "Architecture Orientation", scanInput,
                emptyOrientation(), () -> {
                    AgentReport report = new OrientationAgent(api).orient();
                    ArchitectureOrientation o = new ArchitectureOrienter(api).orient(report);
                    return produced(o, Completeness.COMPLETE,
                            o.majorModules().size() + " major module(s); "
                                    + o.inferredLayers().size() + " inferred layer(s)",
                            List.of(EvidenceRef.of(scan)),
                            List.of("Reused the Repository Orientation Agent; architectural groupings are inferred"));
                });

        List<EntryPointSummary> eps = entryPoints;
        List<BoundarySummary> boundaries = stage(stages, "Java/Ada Boundary Discovery",
                List.of("scan " + scan, "entry points"), List.of(), () -> {
                    List<BoundarySummary> bs = new BoundaryDetector(api).detect(eps);
                    return produced(bs, Completeness.COMPLETE,
                            bs.size() + " boundary(ies) from crossing evidence",
                            bs.stream().limit(6).flatMap(b -> b.evidence().stream()).toList(),
                            List.of("Boundaries require real crossing evidence; name similarity alone is never used"));
                });

        List<BoundarySummary> bnd = boundaries;
        List<RepresentativeLineagePath> lineagePaths = stage(stages, "Data-Lineage Sampling",
                List.of("scan " + scan, "entry points", "boundaries"), List.of(), () -> {
                    List<RepresentativeLineagePath> ps = new LineageSampler(api, options).select(eps, bnd);
                    long partial = ps.stream().filter(RepresentativeLineagePath::partial).count();
                    return produced(ps, ps.isEmpty() ? Completeness.PARTIAL : Completeness.COMPLETE,
                            ps.size() + " representative path(s) (" + partial + " partial)",
                            ps.stream().limit(6).flatMap(p -> p.evidence().stream()).toList(),
                            warn(ps.isEmpty(), "no connected lineage path was sampled"));
                });

        List<RepresentativeLineagePath> paths = lineagePaths;
        List<CentralComponentSummary> central = stage(stages, "Central Component Review",
                List.of("scan " + scan, "entry points", "boundaries", "lineage"), List.of(), () -> {
                    List<CentralComponentSummary> cs =
                            new CentralComponentRanker(api, options).rank(eps, bnd, paths);
                    return produced(cs, cs.isEmpty() ? Completeness.PARTIAL : Completeness.COMPLETE,
                            cs.size() + " central component(s) selected",
                            cs.stream().limit(6).flatMap(c -> c.evidence().stream()).toList(),
                            warn(cs.isEmpty(), "no component had resolved connectivity to rank"));
                });

        List<CentralComponentSummary> comps = central;
        List<OnboardingRisk> risks = stage(stages, "Risk and Gap Review",
                List.of("scan " + scan, "scan health", "central components", "lineage"), List.of(), () -> {
                    List<OnboardingRisk> rs = new RiskReviewer(api).review(health, comps, paths);
                    return produced(rs, Completeness.COMPLETE, rs.size() + " risk(s)/gap(s) categorized",
                            List.of(EvidenceRef.of(scan)),
                            List.of("Unresolved references are analysis limitations, not defects"));
                });

        List<ReadingRecommendation> readingOrder = stage(stages, "Suggested Reading Order",
                List.of("scan " + scan, "entry points", "boundaries"), List.of(), () -> {
                    List<ReadingRecommendation> rr = new ReadingOrderPlanner(api).plan(eps, bnd);
                    return produced(rr, rr.isEmpty() ? Completeness.PARTIAL : Completeness.COMPLETE,
                            rr.size() + " reading step(s)",
                            rr.stream().limit(6).flatMap(r -> r.evidence().stream()).toList(),
                            warn(rr.isEmpty(), "no readable component was found"));
                });

        List<ExpertQuestion> questions = stage(stages, "Questions for Subject-Matter Experts",
                List.of("boundaries", "central components", "lineage", "entry points"), List.of(), () -> {
                    List<ExpertQuestion> qs =
                            new ExpertQuestionGenerator(api).generate(bnd, comps, paths, eps);
                    return produced(qs, Completeness.COMPLETE, qs.size() + " evidence-backed question(s)",
                            qs.stream().limit(6).flatMap(q -> q.evidence().stream()).toList(),
                            List.of("Questions are generated only for roles the evidence supports"));
                });

        FinalSummary summary = stage(stages, "Final Onboarding Summary",
                List.of("all prior stages"), emptySummary(), () -> {
                    FinalSummary fs = new FinalSummarizer().summarize(intake, health, inventory, eps,
                            orientation, bnd, paths, comps, risks, questions);
                    return produced(fs, Completeness.COMPLETE, fs.answers().size() + " summary answer(s)",
                            List.of(EvidenceRef.of(scan)), List.of());
                });

        String status = overallStatus(health, stages);
        return new OnboardingResult(scan, status, options, intake, health, inventory, entryPoints,
                orientation, boundaries, lineagePaths, central, risks, readingOrder, questions, summary,
                stages, options.toolVersion(), List.of(WORKFLOW_VERSION), KNOWN_LIMITATIONS);
    }

    // ---- staging helper ----

    private <T> T stage(List<OnboardingStageResult> stages, String name, List<String> inputs,
                        T fallback, Supplier<Produced<T>> body) {
        long t0 = System.nanoTime();
        try {
            Produced<T> p = body.get();
            stages.add(new OnboardingStageResult(name, p.completeness, inputs, p.output,
                    p.evidence, p.warnings, millis(t0)));
            return p.value;
        } catch (RuntimeException e) {
            // Preserve report shape so later independent stages can still run.
            stages.add(new OnboardingStageResult(name, Completeness.FAILED, inputs,
                    "stage failed: " + e.getClass().getSimpleName(),
                    List.of(), List.of("error: " + String.valueOf(e.getMessage())), millis(t0)));
            return fallback;
        }
    }

    private static <T> Produced<T> produced(T value, Completeness completeness, String output,
                                            List<EvidenceRef> evidence, List<String> warnings) {
        return new Produced<>(value, completeness, output, evidence, warnings);
    }

    private static List<String> warn(boolean condition, String message) {
        return condition ? List.of(message) : List.of();
    }

    private static long millis(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private String overallStatus(ScanHealthSummary health, List<OnboardingStageResult> stages) {
        if (health.status().equals(ScanHealthSummary.FAILED)) {
            return "FAILED";
        }
        boolean anyFailed = stages.stream().anyMatch(s -> s.completeness() == Completeness.FAILED);
        if (anyFailed) {
            return "PARTIAL";
        }
        return health.status();
    }

    // ---- fallbacks (used only when a stage throws) ----

    private RepositoryIntake fallbackIntake(String scan) {
        return new RepositoryIntake(options.repositoryKey(), options.repositoryDisplayName(),
                options.sanitizedLocation(), options.branch(), scan, options.storageMode(),
                options.toolVersion(), api.schemaVersion(), List.of(), List.of(), 0);
    }

    private static ScanHealthSummary fallbackHealth() {
        return new ScanHealthSummary(ScanHealthSummary.FAILED, 0, 0, -1, -1, -1, -1, 0, 0, 0, 0,
                List.of("scan-health assessment failed"), false);
    }

    private static ArchitectureOrientation emptyOrientation() {
        return new ArchitectureOrientation(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static FinalSummary emptySummary() {
        return new FinalSummary(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** Internal per-stage production: the value plus how to describe the stage. */
    private record Produced<T>(T value, Completeness completeness, String output,
                               List<EvidenceRef> evidence, List<String> warnings) {
    }
}
