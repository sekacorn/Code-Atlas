package com.codeatlas.cli;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.reporting.LineageJsonWriter;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.ToolJsonWriter;
import com.codeatlas.tools.ToolResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code atlas tool <operation>} — the agent tool API over the persisted index,
 * as deterministic JSON on stdout. Read-only by construction: the command opens
 * the index with database-level read-only access and never sees the repository.
 *
 * <p>Operations: find_entity, get_entity, search_entities, get_source_evidence,
 * get_callers, get_callees, get_dependencies, get_dependents, get_data_sources,
 * get_data_sinks, get_database_references, trace_data_lineage,
 * calculate_change_impact, find_dead_code_candidates, get_complexity,
 * get_repository_summary, get_unresolved_references, get_diagnostics,
 * get_build_membership, get_configuration_references.
 */
@Command(name = "tool",
        mixinStandardHelpOptions = true,
        description = "Run a read-only agent-tool operation against the persisted index (JSON output).")
public final class ToolCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Operation name (snake_case, see command description).")
    private String operation;

    @Option(names = {"--repo"}, description = "Repository whose default index to query (default: current directory).")
    private Path repository = Path.of(".");

    @Option(names = {"--index"}, description = "Explicit index path (overrides --repo derivation).")
    private Path indexPath;

    @Option(names = {"--id"}, description = "Stable entity id argument.")
    private String id;

    @Option(names = {"--query"}, description = "Query text (find_entity / search_entities).")
    private String query;

    @Option(names = {"--kind"}, description = "Entity kind filter for search_entities.")
    private String kind;

    @Option(names = {"--language"}, description = "Language filter for search_entities.")
    private String language;

    @Option(names = {"--limit"}, description = "Maximum results (default: 50).")
    private int limit = AtlasToolApi.DEFAULT_LIMIT;

    @Option(names = {"--direction"}, description = "trace_data_lineage: downstream, upstream or both (default: downstream).")
    private String direction = "downstream";

    @Option(names = {"--max-depth"}, description = "Traversal depth for lineage/impact (default: 8 / 5).")
    private int maxDepth = -1;

    @Option(names = {"--include-inferred"}, description = "trace_data_lineage: include inferred edges.")
    private boolean includeInferred;

    @Option(names = {"--min-confidence"}, description = "trace_data_lineage: confidence floor (default: 0.40).")
    private double minConfidence = 0.40;

    @Override
    public Integer call() {
        Path index = indexPath != null ? indexPath : IndexLocations.defaultIndexFor(repository);
        if (indexPath == null && !Files.exists(index.getParent() != null ? index.getParent() : index)) {
            System.err.println("No index found for " + repository.toAbsolutePath()
                    + " — run 'atlas scan " + repository + "' first.");
            return 4;
        }
        try (AtlasToolApi api = AtlasToolApi.open(index)) {
            return dispatch(api);
        } catch (IllegalStateException | com.codeatlas.index.IndexException e) {
            System.err.println(e.getMessage());
            return 4;
        }
    }

    private Integer dispatch(AtlasToolApi api) {
        ToolJsonWriter writer = new ToolJsonWriter();
        ToolResult<?> result = switch (operation) {
            case "find_entity" -> api.findEntity(require(query != null ? query : id, "--query"));
            case "get_entity" -> api.getEntity(require(id, "--id"));
            case "search_entities" -> api.searchEntities(require(query, "--query"), kind, language, limit);
            case "get_source_evidence" -> api.getSourceEvidence(require(id, "--id"));
            case "get_callers" -> api.getCallers(require(id, "--id"), limit);
            case "get_callees" -> api.getCallees(require(id, "--id"), limit);
            case "get_dependencies" -> api.getDependencies(require(id, "--id"), limit);
            case "get_dependents" -> api.getDependents(require(id, "--id"), limit);
            case "get_members" -> api.getMembers(require(id, "--id"), limit);
            case "get_data_sources" -> api.getDataSources();
            case "get_data_sinks" -> api.getDataSinks();
            case "get_database_references" -> api.getDatabaseReferences(id, limit);
            case "calculate_change_impact" ->
                    api.calculateChangeImpact(require(id, "--id"), maxDepth > 0 ? maxDepth : 5, limit);
            case "find_dead_code_candidates" -> api.findDeadCodeCandidates(limit);
            case "get_complexity" -> api.getComplexity(limit);
            case "get_repository_summary" -> api.getRepositorySummary();
            case "get_unresolved_references" -> api.getUnresolvedReferences(limit);
            case "get_diagnostics" -> api.getDiagnostics();
            case "get_build_membership" -> api.getBuildMembership(id);
            case "get_configuration_references" -> api.getConfigurationReferences(id, limit);
            case "trace_data_lineage" -> null; // handled below: dedicated lineage JSON format
            default -> {
                System.err.println("Unknown operation '" + operation + "'. See 'atlas tool --help'.");
                yield null;
            }
        };

        if (operation.equals("trace_data_lineage")) {
            LineageQuery.Direction dir = switch (direction.toLowerCase()) {
                case "upstream" -> LineageQuery.Direction.UPSTREAM;
                case "both" -> LineageQuery.Direction.BOTH;
                default -> LineageQuery.Direction.DOWNSTREAM;
            };
            LineageQuery lineageQuery = new LineageQuery(require(id, "--id"), dir,
                    maxDepth > 0 ? maxDepth : 8, includeInferred, minConfidence);
            var traced = api.traceDataLineage(lineageQuery);
            System.out.print(new LineageJsonWriter().render(traced.scanId(), lineageQuery, traced.value()));
            return 0;
        }
        if (result == null) {
            return 2;
        }
        System.out.print(writer.render(operation, result));
        return 0;
    }

    private static String require(String value, String flag) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("This operation requires " + flag);
        }
        return value;
    }
}
