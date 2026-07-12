package com.codeatlas.cli;

import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.RepositoryMetrics;
import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.core.PipelineResult;
import com.codeatlas.reporting.ReportBundle;
import com.codeatlas.scanner.ScanOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code atlas scan <repo>} &mdash; scan a repository, analyse it, and write reports.
 */
@Command(name = "scan",
        mixinStandardHelpOptions = true,
        description = "Scan a repository, build the software model, analyse it and write reports.")
public final class ScanCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the repository root to analyse.")
    private Path repository;

    @Option(names = {"-o", "--out"}, description = "Output directory for reports (default: ./atlas-report).")
    private Path outputDir = Path.of("atlas-report");

    @Option(names = {"--index"}, description = "Path to the persistent H2 index "
            + "(default: ~/.code-atlas/index/<repo>-<hash>/atlas).")
    private Path indexPath;

    @Option(names = {"--in-memory"}, description = "Use a transient in-memory index (explicit temporary session; "
            + "nothing persists across runs).")
    private boolean inMemory;

    @Option(names = {"--complexity-threshold"}, description = "Cyclomatic complexity threshold (default: 10).")
    private int complexityThreshold = 10;

    @Option(names = {"--min-confidence"}, description = "Minimum dead-code confidence to report, 0-100 (default: 60).")
    private int minConfidence = 60;

    @Option(names = {"--threads"}, description = "Parallel worker threads (default: CPU count).")
    private int threads = 0;

    @Override
    public Integer call() {
        if (!repository.toFile().isDirectory()) {
            System.err.println("Not a directory: " + repository);
            return 2;
        }

        ScanOptions.Builder scan = ScanOptions.builder();
        if (threads > 0) {
            scan.threads(threads);
        }
        // Persistent file-backed storage is the default; in-memory only on request.
        Path effectiveIndex = null;
        if (!inMemory) {
            effectiveIndex = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
            try {
                java.nio.file.Files.createDirectories(effectiveIndex.toAbsolutePath().getParent());
            } catch (java.io.IOException e) {
                System.err.println("Cannot create index directory: " + e.getMessage());
                return 2;
            }
        }
        PipelineConfig config = PipelineConfig.builder()
                .scanOptions(scan.build())
                .complexityThreshold(complexityThreshold)
                .deadCodeMinConfidence(minConfidence)
                .indexPath(effectiveIndex)
                .build();

        System.out.println("Scanning " + repository.toAbsolutePath() + " ...");
        System.out.println(effectiveIndex != null
                ? "Index: " + effectiveIndex.toAbsolutePath()
                : "Index: in-memory (temporary session)");
        PipelineResult result = CodeAtlasPipeline.withDiscoveredParsers().run(repository, config);

        printSummary(result);

        List<Path> written = new ReportBundle().writeAll(result.reportData(), outputDir);
        System.out.println();
        System.out.println("Reports written to " + outputDir.toAbsolutePath() + ":");
        written.forEach(p -> System.out.println("  " + p.getFileName()));
        System.out.println();
        System.out.println("Open " + outputDir.resolve("report.html").toAbsolutePath() + " in a browser.");
        return 0;
    }

    private void printSummary(PipelineResult result) {
        AnalysisResult a = result.analysis();
        RepositoryMetrics m = a.metrics();
        String bar = "-".repeat(52);
        System.out.println();
        System.out.println(bar);
        System.out.printf("  Code Atlas - %s%n", result.reportData().repositoryName());
        System.out.println(bar);
        System.out.printf("  Files ............... %,d%n", m.totalFiles());
        System.out.printf("  Lines (code/total) .. %,d / %,d%n", m.codeLines(), m.totalLines());
        System.out.print("  Languages ........... ");
        System.out.println(m.filesByLanguage().isEmpty() ? "none"
                : m.filesByLanguage().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue()).reduce((x, y) -> x + ", " + y).orElse(""));
        System.out.printf("  Classes/Types ....... %,d%n",
                m.countOf(com.codeatlas.model.EntityKind.CLASS)
                        + m.countOf(com.codeatlas.model.EntityKind.RECORD)
                        + m.countOf(com.codeatlas.model.EntityKind.ENUM)
                        + m.countOf(com.codeatlas.model.EntityKind.TYPE));
        System.out.printf("  Methods/Subprograms . %,d%n",
                m.countOf(com.codeatlas.model.EntityKind.METHOD)
                        + m.countOf(com.codeatlas.model.EntityKind.FUNCTION)
                        + m.countOf(com.codeatlas.model.EntityKind.PROCEDURE));
        System.out.printf("  Complexity hotspots . %,d%n", a.complexityHotspots().size());
        System.out.printf("  Dead-code candidates  %,d (%d%% of units)%n", a.deadCode().size(), a.deadCodePercent());
        var lin = a.lineage().coverage();
        if (lin.endpointsDetected() > 0 || lin.entitiesMappedToTables() > 0) {
            System.out.printf("  Lineage ............. %d endpoint(s), %d with a store path, %d table(s)%n",
                    lin.endpointsDetected(), lin.endpointsWithStorePath(),
                    a.lineage().stores().size());
        }
        System.out.println(bar);
        var cov = result.coverage();
        System.out.printf("  Scan id ............. %s%n", result.scanId());
        System.out.println("  Coverage:");
        System.out.printf("    Files analyzed .... %,d / %,d  (%,d skipped, %,d failed, %,d reused from cache)%n",
                cov.filesAnalyzed(), cov.filesDiscovered(), cov.filesSkipped(), cov.filesFailed(),
                cov.filesReused());
        System.out.printf("    References resolved %d%%  (%,d resolved, %,d unresolved, %,d ambiguous)%n",
                cov.resolutionRatePercent(), cov.referencesResolved(),
                cov.referencesUnresolved(), cov.referencesAmbiguous());
        System.out.printf("    Scan status ....... %s%n", cov.isPartial() ? "PARTIAL" : "COMPLETE");
        var diagnostics = result.model().diagnostics();
        if (!diagnostics.isEmpty()) {
            System.out.printf("    Diagnostics ....... %,d (e.g. stable-id collisions)%n", diagnostics.size());
        }
        System.out.println(bar);

        if (!a.complexityHotspots().isEmpty()) {
            System.out.println("  Top complexity:");
            for (ComplexityHotspot h : a.complexityHotspots().stream().limit(3).toList()) {
                System.out.printf("    %-40s %3d  %s%n",
                        truncate(h.qualifiedName(), 40), h.complexity(), h.location());
            }
        }
        if (!a.deadCode().isEmpty()) {
            System.out.println("  Top dead-code candidates:");
            for (DeadCodeCandidate c : a.deadCode().stream().limit(3).toList()) {
                System.out.printf("    %-40s %2d%%  %s%n",
                        truncate(c.qualifiedName(), 40), c.confidence(), c.location());
            }
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
