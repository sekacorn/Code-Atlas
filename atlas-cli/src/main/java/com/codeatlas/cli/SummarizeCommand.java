package com.codeatlas.cli;

import com.codeatlas.agents.AgentAnswer;
import com.codeatlas.agents.EntitySummarizer;
import com.codeatlas.agents.OrientationReport;
import com.codeatlas.tools.AtlasToolApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code atlas summarize <id>} — deterministic method/component summary from the
 * persisted index: confirmed facts (parameters, calls, reads, writes, contracts)
 * with cited evidence, inferred statements explicitly labelled. No LLM involved.
 */
@Command(name = "summarize",
        mixinStandardHelpOptions = true,
        description = "Deterministic summary of a method, subprogram or component by stable id.")
public final class SummarizeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Stable entity id (e.g. java:method:com.x.S#run(), ada:package:Nav).")
    private String stableId;

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
            AgentAnswer answer = new EntitySummarizer(api).summarize(stableId);
            if (format.equalsIgnoreCase("json")) {
                System.out.print(new OrientationReport(api.scanId(), List.of(answer)).toJson());
            } else {
                System.out.print(answer.toText());
            }
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
