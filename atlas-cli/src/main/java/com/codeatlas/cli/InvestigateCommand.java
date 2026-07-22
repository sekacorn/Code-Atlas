package com.codeatlas.cli;

import com.codeatlas.agents.AgentReport;
import com.codeatlas.agents.LineageInvestigatorAgent;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code atlas investigate <start>} — the Data-Lineage Investigator Agent in
 * deterministic mode: where does this data originate, what transforms it, where
 * is it stored, who consumes it, and which parts of the path are unresolved.
 * Accepts a stable id, an endpoint shorthand ({@code "POST /customers"}) or a
 * unique name suffix. Read-only; no LLM involved.
 */
@Command(name = "investigate",
        mixinStandardHelpOptions = true,
        description = "Run the deterministic Data-Lineage Investigator Agent for one entity.")
public final class InvestigateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Entity to investigate: stable id, \"POST /path\", or unique name suffix.")
    private String start;

    @Option(names = {"--repo"}, description = "Repository whose default index to query (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

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
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            var found = api.findEntity(start);
            Optional<Views.EntityView> entity = found.value();
            if (entity.isEmpty()) {
                System.err.println(found.note());
                return 3;
            }
            AgentReport report = new LineageInvestigatorAgent(api).investigate(entity.get().stableId());
            System.out.print(format.equalsIgnoreCase("json") ? report.toJson() : report.toText());
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 3;
        } catch (IllegalStateException | com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }
}
