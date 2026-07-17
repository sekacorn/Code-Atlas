package com.codeatlas.cli;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.core.PipelineResult;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.onboarding.OnboardingReport;
import com.codeatlas.onboarding.OnboardingReportWriter;
import com.codeatlas.onboarding.OnboardingService;
import com.codeatlas.onboarding.model.OnboardingOptions;
import com.codeatlas.onboarding.model.OnboardingResult;
import com.codeatlas.scanner.ScanOptions;
import com.codeatlas.tools.AtlasToolApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code atlas onboard <repository>} - the guided, deterministic repository
 * onboarding workflow. Runs (or reuses) a scan, then runs the twelve read-only
 * onboarding stages and writes an evidence-backed onboarding package
 * ({@code onboarding-report.json/.html/.txt}) outside the analyzed repository.
 *
 * <p>Read-only by construction: the onboarding stages query the persisted index
 * only; reports are written to {@code --output} (outside the repository); the
 * analyzed repository is never modified and its code is never executed.
 */
@Command(name = "onboard",
        mixinStandardHelpOptions = true,
        description = "Guided, deterministic onboarding for an unfamiliar Java/Ada repository.")
public final class OnboardCommand implements Callable<Integer> {

    private static final String TOOL_VERSION = "Code Atlas 0.2.0";

    @Parameters(index = "0", description = "Path to the repository root to onboard.")
    private Path repository;

    @Option(names = {"--index"}, description = "Explicit index path (default: ~/.code-atlas/index/<repo>-<hash>/atlas).")
    private Path indexPath;

    @Option(names = {"--scan"}, description = "Force a fresh scan even if a completed scan exists.")
    private boolean scan;

    @Option(names = {"--reuse-latest"}, description = "Reuse the latest completed scan when compatible (default).")
    private boolean reuseLatest;

    @Option(names = {"--full-rebuild"}, description = "Discard the cached index and reparse everything.")
    private boolean fullRebuild;

    @Option(names = {"--max-components"}, description = "Max central components to review (default: 10).")
    private int maxComponents = OnboardingOptions.DEFAULT_MAX_COMPONENTS;

    @Option(names = {"--max-paths"}, description = "Max representative lineage paths (default: 5).")
    private int maxPaths = OnboardingOptions.DEFAULT_MAX_PATHS;

    @Option(names = {"--include-inferred"}, description = "Include inferred lineage edges in sampled paths.")
    private boolean includeInferred;

    @Option(names = {"--min-confidence"}, description = "Minimum lineage edge confidence, 0-100 (default: 40).")
    private int minConfidence = 40;

    @Option(names = {"--format"}, description = "What to print to stdout: text (default), json, or html.")
    private String format = "text";

    @Option(names = {"-o", "--output"}, description = "Report output directory (default: "
            + "./atlas-onboarding-report, or ~/.code-atlas/onboarding/<repo> when that would fall "
            + "inside the analyzed repository). Must be outside the repository.")
    private Path outputDir;

    @Override
    public Integer call() {
        if (!repository.toFile().isDirectory()) {
            System.err.println("Not a directory: " + repository);
            return 2;
        }
        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        Path reportDir;
        try {
            reportDir = resolveOutputDir(repository);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        AnalysisCoverage exactCoverage = null;
        try {
            if (fullRebuild) {
                deleteIndexFiles(index);
            }
            boolean needScan = fullRebuild || scan || !completedScanExists(index);
            if (needScan) {
                System.out.println("Scanning " + repository.toAbsolutePath() + " ...");
                PipelineResult result = runScan(repository, index);
                exactCoverage = result.coverage();
                System.out.println("Scan " + result.scanId() + " complete; "
                        + result.coverage().filesAnalyzed() + " file(s) analyzed.");
            } else {
                System.out.println("Reusing the latest completed scan for " + repository.toAbsolutePath());
            }
        } catch (RuntimeException | IOException e) {
            System.err.println("Scan failed: " + e.getMessage());
            return 4;
        }

        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            OnboardingOptions options = new OnboardingOptions(
                    repository.toAbsolutePath().getFileName().toString(),
                    repository.toAbsolutePath().getFileName().toString(),
                    IndexLocations.repositoryKey(repository), branchOf(repository), "file-backed",
                    TOOL_VERSION, maxComponents, maxPaths, includeInferred,
                    Math.max(0, Math.min(100, minConfidence)) / 100.0);
            OnboardingResult result = new OnboardingService(api, options, exactCoverage).run();

            String generatedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            List<Path> written = new OnboardingReportWriter().writeAll(result, reportDir, generatedAt);

            if (format.equalsIgnoreCase("json")) {
                System.out.println(OnboardingReport.toJson(result));
            } else if (format.equalsIgnoreCase("html")) {
                System.out.println("HTML report written (see below).");
            } else {
                System.out.println();
                System.out.println(OnboardingReport.toText(result));
            }
            System.out.println();
            System.out.println("Onboarding reports written to " + reportDir.toAbsolutePath() + ":");
            written.forEach(p -> System.out.println("  " + p.getFileName()));
            return 0;
        } catch (IllegalStateException | com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    /**
     * Reports must never land inside the analyzed repository. An explicit
     * {@code --output} inside it is refused outright; the <em>default</em> instead
     * falls back to {@code ~/.code-atlas/onboarding/<repo>} so the natural
     * {@code atlas onboard .} (run from the repository root) still works.
     */
    private Path resolveOutputDir(Path repository) {
        if (outputDir != null) {
            OnboardingReportWriter.ensureOutsideRepository(outputDir, repository);
            return outputDir;
        }
        Path local = Path.of("atlas-onboarding-report").toAbsolutePath().normalize();
        if (!OnboardingReportWriter.isInsideRepository(local, repository)) {
            return local;
        }
        Path safe = IndexLocations.defaultOnboardingOutputFor(repository);
        System.out.println("Note: ./atlas-onboarding-report would fall inside the analyzed repository; "
                + "writing reports to " + safe + " instead.");
        return safe;
    }

    // ---- scan-or-reuse ----

    private boolean completedScanExists(Path index) {
        try (AtlasStore store = AtlasStore.atPathReadOnly(index)) {
            return store.latestCompletedScan().isPresent();
        } catch (RuntimeException e) {
            return false; // no index, unreadable, or schema mismatch -> a scan is needed
        }
    }

    private PipelineResult runScan(Path repository, Path index) throws IOException {
        Path parent = index.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        PipelineConfig config = PipelineConfig.builder()
                .scanOptions(ScanOptions.builder().build())
                .indexPath(index)
                .build();
        return CodeAtlasPipeline.withDiscoveredParsers().run(repository, config);
    }

    /** Clears the persistent H2 index files so the next scan reparses everything. */
    private void deleteIndexFiles(Path index) throws IOException {
        for (String suffix : List.of(".mv.db", ".trace.db", ".lock.db")) {
            Files.deleteIfExists(Path.of(index.toString() + suffix));
        }
    }

    /** Best-effort current branch from {@code .git/HEAD}; empty when unavailable. */
    private static String branchOf(Path repository) {
        try {
            Path head = repository.resolve(".git").resolve("HEAD");
            if (!Files.isRegularFile(head)) {
                return "";
            }
            String content = Files.readString(head, StandardCharsets.UTF_8).trim();
            return content.startsWith("ref: refs/heads/")
                    ? content.substring("ref: refs/heads/".length()) : "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
