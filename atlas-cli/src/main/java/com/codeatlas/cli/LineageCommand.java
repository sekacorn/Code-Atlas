package com.codeatlas.cli;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.analysis.lineage.LineageService;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.index.ScanRecord;
import com.codeatlas.model.Entity;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.reporting.LineageJsonWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code atlas lineage <start>} — trace where data comes from or goes to, using
 * the persisted index of the latest completed scan. Works offline, read-only,
 * and never rescans: run {@code atlas scan} first.
 *
 * <p>The start entity may be a stable id ({@code java:type:com.x.CustomerResponse},
 * {@code sql:table:customer}), an endpoint shorthand ({@code "POST /customers"}),
 * or a qualified-name suffix unique within the scan.
 */
@Command(name = "lineage",
        mixinStandardHelpOptions = true,
        description = "Trace evidence-backed data lineage from an entity, endpoint or table.")
public final class LineageCommand implements Callable<Integer> {

    private static final Pattern ENDPOINT_SHORTHAND =
            Pattern.compile("^(GET|POST|PUT|PATCH|DELETE)\\s+(/\\S*)$", Pattern.CASE_INSENSITIVE);

    @Parameters(index = "0", description = "Start entity: stable id, \"POST /path\" shorthand, or unique name suffix.")
    private String start;

    @Option(names = {"--repo"}, description = "Repository whose default index to query (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

    @Option(names = {"--upstream"}, description = "Trace where the data comes from.")
    private boolean upstream;

    @Option(names = {"--downstream"}, description = "Trace where the data goes (default).")
    private boolean downstream;

    @Option(names = {"--both"}, description = "Trace both directions.")
    private boolean both;

    @Option(names = {"--max-depth"}, description = "Maximum path length in edges (default: 8).")
    private int maxDepth = 8;

    @Option(names = {"--include-inferred"}, description = "Include naming/convention-inferred edges (excluded by default).")
    private boolean includeInferred;

    @Option(names = {"--min-confidence"}, description = "Minimum edge confidence 0..1 (default: 0.40).")
    private double minConfidence = 0.40;

    @Option(names = {"--scan"}, description = "Scan id the query must match (only the latest completed scan is retained).")
    private String scanId;

    @Option(names = {"--format"}, description = "Output format: text or json (default: text).")
    private String format = "text";

    @Override
    public Integer call() {
        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        if (indexPath == null && !IndexLocations.indexDirectoryExists(index)) {
            System.err.println("No index found for " + repository.toAbsolutePath()
                    + " — run 'atlas scan " + repository + "' first.");
            return 4;
        }
        try (AtlasStore store = AtlasStore.atPathReadOnly(index)) {
            ScanRecord latest = store.latestCompletedScan().orElse(null);
            if (latest == null) {
                System.err.println("The index has no completed scan — run 'atlas scan' first.");
                return 4;
            }
            if (scanId != null && !scanId.equals(latest.scanKey())) {
                System.err.println("Requested scan " + scanId + " is not available; only the latest completed scan ("
                        + latest.scanKey() + ") is retained. Re-run without --scan or rescan the repository.");
                return 4;
            }

            SoftwareModel model = store.loadModel();
            String startId = resolveStart(model);
            if (startId == null) {
                return 3;
            }

            LineageQuery.Direction direction = both ? LineageQuery.Direction.BOTH
                    : upstream && !downstream ? LineageQuery.Direction.UPSTREAM
                    : LineageQuery.Direction.DOWNSTREAM;
            LineageQuery query = new LineageQuery(startId, direction, maxDepth, includeInferred, minConfidence);
            LineageResult result = new LineageService().trace(model, query);

            if (format.equalsIgnoreCase("json")) {
                System.out.print(new LineageJsonWriter().render(latest.scanKey(), query, result));
            } else {
                printText(model, latest, query, result);
            }
            return 0;
        }
    }

    /** Resolves the CLI start argument to a stable id, printing candidates when ambiguous. */
    private String resolveStart(SoftwareModel model) {
        Matcher endpoint = ENDPOINT_SHORTHAND.matcher(start.trim());
        if (endpoint.matches()) {
            String id = "java:endpoint:" + endpoint.group(1).toUpperCase(Locale.ROOT) + ":" + endpoint.group(2);
            if (model.entity(id).isPresent()) {
                return id;
            }
            System.err.println("No endpoint " + endpoint.group(1).toUpperCase(Locale.ROOT) + " "
                    + endpoint.group(2) + " in the latest scan.");
            return null;
        }
        if (start.contains(":")) {
            if (model.entity(start).isPresent()) {
                return start;
            }
            System.err.println("No entity with stable id '" + start + "' in the latest scan.");
            return null;
        }
        List<Entity> matches = model.entities().stream()
                .filter(e -> e.qualifiedName().equals(start) || e.qualifiedName().endsWith("." + start)
                        || e.qualifiedName().endsWith("#" + start) || e.name().equals(start))
                .sorted(java.util.Comparator.comparing(Entity::id))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0).id();
        }
        if (matches.isEmpty()) {
            System.err.println("No entity matches '" + start + "'.");
        } else {
            System.err.println("'" + start + "' is ambiguous; candidates:");
            matches.stream().limit(10).forEach(e -> System.err.println("  " + e.id()));
        }
        return null;
    }

    private void printText(SoftwareModel model, ScanRecord latest, LineageQuery query, LineageResult result) {
        String bar = "-".repeat(64);
        System.out.println(bar);
        System.out.println("  Lineage: " + result.startId());
        System.out.printf("  Direction: %s   Scan: %s   Max depth: %d%n",
                query.direction(), latest.scanKey(), query.maxDepth());
        System.out.printf("  Filters: min confidence %.2f, inferred edges %s%n",
                query.minConfidence(), query.includeInferred() ? "included" : "excluded");
        System.out.println(bar);

        if (result.paths().isEmpty()) {
            System.out.println("  No lineage paths found from this entity with the current filters.");
        }
        int n = 1;
        for (LineageResult.Path path : result.paths()) {
            System.out.printf("  Path %d (confidence %.2f):%n", n++, path.minConfidence());
            List<String> nodes = path.nodeIds();
            for (int i = 0; i < nodes.size(); i++) {
                String label = model.entity(nodes.get(i))
                        .map(e -> e.kind() + "  " + (e.kind().name().equals("DATABASE_OBJECT")
                                ? "table: " + e.name() : e.qualifiedName()))
                        .orElse(nodes.get(i));
                if (i == 0) {
                    System.out.println("    " + label);
                } else {
                    LineageResult.Edge edge = path.edges().get(i - 1);
                    System.out.printf("      -[%s %.2f %s]-> %s%n",
                            edge.kind().toLowerCase(Locale.ROOT), edge.confidence(),
                            edge.status().toLowerCase(Locale.ROOT), label);
                    if (!edge.location().isBlank()) {
                        System.out.println("         evidence: " + edge.location()
                                + (edge.ruleId().isBlank() ? "" : "  rule: " + edge.ruleId()));
                    }
                }
            }
            System.out.println();
        }

        if (!result.gaps().isEmpty()) {
            System.out.println("  Unresolved:");
            for (LineageResult.Gap gap : result.gaps()) {
                System.out.println("    - [" + gap.kind() + "] " + gap.description());
            }
        }
        if (result.cyclesDetected()) {
            System.out.println("  Note: a dependency cycle was cut during traversal.");
        }
        if (result.truncated()) {
            System.out.println("  Note: traversal was truncated (depth or path limit).");
        }
        System.out.println(bar);
    }
}
