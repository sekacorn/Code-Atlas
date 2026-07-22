package com.codeatlas.cli;

import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.graph.GraphExporter;
import com.codeatlas.graph.GraphType;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.ui.ExplorerServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code atlas serve} — opens the local explorer: search the model, open an entity,
 * and click through its callers, dependencies and data lineage in a browser.
 *
 * <p>The explorer binds to loopback only, serves views from the database-level
 * read-only index, answers {@code GET} only, and embeds no external assets — so it
 * runs on an offline or locked-down workstation and cannot modify anything.
 */
@Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Open the local read-only explorer (search and navigate the model in a browser).")
public final class ServeCommand implements Callable<Integer> {

    @ParentCommand
    private AtlasCli parent;

    @Option(names = {"--repo"}, description = "Repository whose index to explore (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

    @Option(names = {"-p", "--port"}, description = "Port on 127.0.0.1 (default: 8138; 0 picks a free one).")
    private int port = 8138;

    @Option(names = {"--no-graphs"}, description = "Skip loading the model for graph pages (starts faster).")
    private boolean noGraphs;

    @Override
    public Integer call() {
        if (parent != null && parent.hardened()) {
            System.err.println("The local explorer is disabled in hardened mode. Use the generated HTML report.");
            return 4;
        }
        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        if (indexPath == null && !IndexLocations.indexDirectoryExists(index)) {
            System.err.println("No index found for " + repository.toAbsolutePath()
                    + " — run 'atlas scan " + repository + "' first.");
            return 4;
        }
        String name = IndexLocations.repositoryDisplayName(repository);

        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            ExplorerServer.GraphRenderer graphs = noGraphs
                    ? ExplorerServer.noGraphs("Graphs were disabled with --no-graphs.")
                    : graphRenderer(index);

            try (ExplorerServer server = new ExplorerServer(api, name, port, graphs)) {
                server.start();
                System.out.println("Code Atlas explorer (read-only, loopback only): " + server.url());
                System.out.println("Search from the box at the top, then click through the graph.");
                System.out.println("Press Ctrl+C to stop.");
                // Serve until interrupted; the try-with-resources stops the server.
                Thread.currentThread().join();
                return 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IllegalStateException | com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    /**
     * Graph pages need the model itself, which the tool API deliberately never hands
     * out. The model is loaded once through a separate read-only connection and the
     * graphs are rendered from it on demand.
     */
    private ExplorerServer.GraphRenderer graphRenderer(Path index) {
        try (AtlasStore store = AtlasStore.atPathReadOnly(index)) {
            SoftwareModel model = store.loadModel();
            AnalysisResult analysis = new AnalysisEngine().analyze(model);
            GraphExporter exporter = new GraphExporter();
            return ExplorerServer.safely(type ->
                    exporter.export(model, analysis, GraphType.from(type), GraphExporter.Format.SVG));
        } catch (RuntimeException e) {
            return ExplorerServer.noGraphs("Graphs unavailable: " + e.getMessage());
        }
    }
}
