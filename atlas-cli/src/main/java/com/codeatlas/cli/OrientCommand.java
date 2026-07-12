package com.codeatlas.cli;

import com.codeatlas.agents.OrientationAgent;
import com.codeatlas.agents.OrientationReport;
import com.codeatlas.tools.AtlasToolApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code atlas orient} — the Repository Orientation Agent in deterministic mode.
 * Answers "where do I start?" questions from the persisted index through the
 * read-only tool API, with confirmed facts separated from inferred findings and
 * every statement backed by cited evidence. No LLM involved.
 */
@Command(name = "orient",
        mixinStandardHelpOptions = true,
        description = "Run the deterministic Repository Orientation Agent over the persisted index.")
public final class OrientCommand implements Callable<Integer> {

    @Option(names = {"--repo"}, description = "Repository whose default index to query (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

    @Option(names = {"--format"}, description = "Output format: text or json (default: text).")
    private String format = "text";

    @Override
    public Integer call() {
        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        if (indexPath == null && !Files.exists(index.getParent() != null ? index.getParent() : index)) {
            System.err.println("No index found for " + repository.toAbsolutePath()
                    + " — run 'atlas scan " + repository + "' first.");
            return 4;
        }
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            OrientationReport report = new OrientationAgent(api).orient();
            System.out.print(format.equalsIgnoreCase("json") ? report.toJson() : report.toText());
            return 0;
        } catch (IllegalStateException | com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }
}
