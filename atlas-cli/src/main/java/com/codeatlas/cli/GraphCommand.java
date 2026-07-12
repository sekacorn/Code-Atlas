package com.codeatlas.cli;

import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.graph.GraphExporter;
import com.codeatlas.graph.GraphType;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.model.SoftwareModel;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code atlas graph --type <t> --format <f>} — export a graph view of the
 * persisted model as Graphviz DOT or a self-contained SVG. Read-only; prints to
 * stdout or writes to {@code --out}.
 */
@Command(name = "graph",
        mixinStandardHelpOptions = true,
        description = "Export a dependency, call, dead-code or architecture graph (DOT or SVG).")
public final class GraphCommand implements Callable<Integer> {

    @Option(names = {"--type"}, required = true,
            description = "Graph: dependency, call, dead-code, architecture.")
    private String type;

    @Option(names = {"--format"}, description = "Output format: dot or svg (default: svg).")
    private String format = "svg";

    @Option(names = {"--repo"}, description = "Repository whose default index to read (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

    @Option(names = {"-o", "--out"}, description = "Write to this file instead of stdout.")
    private Path out;

    @Override
    public Integer call() {
        GraphType graphType;
        GraphExporter.Format graphFormat;
        try {
            graphType = GraphType.from(type);
            graphFormat = GraphExporter.Format.from(format);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        if (indexPath == null && !Files.exists(index.getParent() != null ? index.getParent() : index)) {
            System.err.println("No index found for " + repository.toAbsolutePath()
                    + " — run 'atlas scan " + repository + "' first.");
            return 4;
        }
        try (AtlasStore store = AtlasStore.atPathReadOnly(index)) {
            if (store.latestCompletedScan().isEmpty()) {
                System.err.println("The index has no completed scan — run 'atlas scan' first.");
                return 4;
            }
            SoftwareModel model = store.loadModel();
            String rendered = new GraphExporter().export(model,
                    new AnalysisEngine().analyze(model), graphType, graphFormat);
            if (out != null) {
                Files.writeString(out, rendered);
                System.out.println("Wrote " + graphType + " graph (" + graphFormat + ") to "
                        + out.toAbsolutePath());
            } else {
                System.out.print(rendered);
            }
            return 0;
        } catch (com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        } catch (java.io.IOException e) {
            System.err.println("Cannot write graph: " + e.getMessage());
            return 5;
        }
    }
}
