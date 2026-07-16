package com.codeatlas.agents;

import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.ToolResult;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Repository Orientation Agent, deterministic mode: answers "where do I
 * start?" questions about an unfamiliar repository using only the read-only tool
 * API - graph traversal, counting and fixed templates. No LLM is involved; every
 * statement is either a confirmed fact backed by cited evidence or an explicitly
 * labelled inference, and analysis gaps are reported, never hidden.
 */
public final class OrientationAgent {

    private static final int CANDIDATE_CAP = 100;

    private final AtlasToolApi api;

    public OrientationAgent(AtlasToolApi api) {
        this.api = api;
    }

    /** Answers all orientation questions in a fixed, deterministic order. */
    public AgentReport orient() {
        List<AgentAnswer> answers = List.of(
                whereShouldIStart(),
                whatAreTheMainModules(),
                whatAreTheLikelyEntryPoints(),
                whatIsMostCentral(),
                whatDataStoresExist(),
                whatExternalSystemsExist(),
                whatShouldIReadFirst(),
                whatCouldNotBeAnalyzed());
        return new AgentReport(api.scanId(), "Repository Orientation (deterministic)", answers);
    }

    // ---- the eight questions ----

    AgentAnswer whereShouldIStart() {
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        List<Views.EntityView> endpoints = endpoints();
        for (Views.EntityView e : endpoints) {
            confirmed.add("HTTP endpoint " + e.name() + " is handled by " + handlerOf(e));
            evidence.add(cite(e));
        }
        List<Views.EntityView> sources = api.getDataSources().value();
        for (Views.EntityView s : sources) {
            confirmed.add("Data enters through " + s.name() + " ("
                    + s.attributes().getOrDefault("description", "input source") + ")");
            evidence.add(cite(s));
        }

        String answer;
        if (!endpoints.isEmpty()) {
            answer = "Start at the HTTP endpoints - they are the discovered entry points; "
                    + "follow " + endpoints.get(0).name() + " downstream with 'atlas lineage'.";
        } else if (!sources.isEmpty()) {
            answer = "Start at the input sources (" + sources.get(0).name()
                    + ") and follow their readers downstream.";
        } else {
            answer = "No endpoints or input sources were discovered; start with the largest "
                    + "package (see the main-modules answer) and its externally exposed subprograms.";
        }
        return new AgentAnswer("Where should I start?", answer, confirmed, List.of(), evidence,
                confidence(confirmed.size(), 0),
                List.of(),
                List.of("Entry points invoked via reflection or schedulers are not detected yet"),
                List.of("atlas lineage \"<endpoint or procedure>\" --downstream"));
    }

    AgentAnswer whatAreTheMainModules() {
        // Rank packages by structural member count (CONTAINS).
        List<Views.EntityView> packages = api.searchEntities("", "PACKAGE", null, CANDIDATE_CAP).value();
        TreeMap<String, Integer> sizes = new TreeMap<>();
        for (Views.EntityView p : packages) {
            sizes.put(p.stableId(), api.getMembers(p.stableId(), 1).totalMatches());
        }
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        sizes.entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(10)
                .forEach(e -> {
                    confirmed.add(e.getKey() + " contains " + e.getValue() + " member(s)");
                    api.getEntity(e.getKey()).value().ifPresent(v -> evidence.add(cite(v)));
                });
        String answer = confirmed.isEmpty()
                ? "No packages were discovered in this repository."
                : "The main modules by structural size: " + firstToken(confirmed) + ".";
        return new AgentAnswer("What are the main modules?", answer, confirmed, List.of(), evidence,
                confidence(confirmed.size(), 0), List.of(),
                List.of("Packages are the structural unit shown here; build modules (Maven/Gradle/GNAT) "
                        + "are parsed separately - see 'atlas tool get_build_membership'"),
                List.of("atlas tool get_members --id <package-id>"));
    }

    AgentAnswer whatAreTheLikelyEntryPoints() {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();

        for (Views.EntityView e : endpoints()) {
            confirmed.add("HTTP endpoint " + e.name()
                    + (Boolean.parseBoolean(e.attributes().getOrDefault("httpPathUnresolved", "false"))
                    ? " (path not statically resolvable)" : ""));
            evidence.add(cite(e));
        }
        for (Views.EntityView m : api.searchEntities("main", "METHOD", "java", CANDIDATE_CAP).value()) {
            if (m.name().equals("main") && m.attributes().getOrDefault("signature", "").startsWith("main(")) {
                confirmed.add("Java main method " + m.qualifiedName());
                evidence.add(cite(m));
            }
        }
        // A build file that declares a main unit is the strongest entry-point evidence
        // there is - no naming or shape heuristic involved.
        for (Views.EntityView module : api.searchEntities("", "MODULE", null, CANDIDATE_CAP).value()) {
            for (Views.NeighborView n : api.getDependencies(module.stableId(), CANDIDATE_CAP).value()) {
                if (n.edge().kind().equals("DECLARES_MAIN")) {
                    confirmed.add("Build-declared main " + n.entity().qualifiedName() + " (declared by "
                            + module.attributes().getOrDefault("buildFile", module.name()) + ")");
                    evidence.add(new AgentAnswer.Citation(n.entity().stableId(), n.edge().evidence()));
                }
            }
        }
        // Ada: procedures reading an input source are likely externally driven.
        for (Views.EntityView source : api.getDataSources().value()) {
            for (Views.NeighborView reader : api.getDependents(source.stableId(), 20).value()) {
                if (reader.edge().kind().equals("READS_FROM")) {
                    inferred.add(reader.entity().qualifiedName() + " reads " + source.name()
                            + " - likely an input-driven entry point (inferred)");
                    evidence.add(new AgentAnswer.Citation(reader.entity().stableId(), reader.edge().evidence()));
                }
            }
        }
        String answer = confirmed.isEmpty() && inferred.isEmpty()
                ? "No entry points were detected within analyzed evidence."
                : "Likely entry points: " + (confirmed.isEmpty() ? "" : confirmed.size() + " discovered")
                + (inferred.isEmpty() ? "" : (confirmed.isEmpty() ? "" : ", plus ")
                + inferred.size() + " inferred from input-source reads") + ".";
        return new AgentAnswer("What are the likely entry points?", answer, confirmed, inferred, evidence,
                confidence(confirmed.size(), inferred.size()),
                List.of(),
                List.of("Scheduled jobs and message listeners are not detected yet; "
                        + "build-declared mains are detected when a build file declares them"),
                List.of("atlas tool get_callers --id <entry-point-id>"));
    }

    AgentAnswer whatIsMostCentral() {
        Set<String> candidates = new TreeSet<>();
        api.searchEntities("", "CLASS", null, CANDIDATE_CAP).value().forEach(v -> candidates.add(v.stableId()));
        api.searchEntities("", "INTERFACE", null, CANDIDATE_CAP).value().forEach(v -> candidates.add(v.stableId()));
        api.searchEntities("", "VARIABLE", null, CANDIDATE_CAP).value().forEach(v -> candidates.add(v.stableId()));

        TreeMap<String, Integer> fanIn = new TreeMap<>();
        for (String id : candidates) {
            int dependents = api.getDependents(id, 1).totalMatches();
            if (dependents > 0) {
                fanIn.put(id, dependents);
            }
        }
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        fanIn.entrySet().stream()
                .sorted(Comparator.<java.util.Map.Entry<String, Integer>>comparingInt(java.util.Map.Entry::getValue)
                        .reversed().thenComparing(java.util.Map.Entry::getKey))
                .limit(5)
                .forEach(e -> {
                    confirmed.add(e.getKey() + " has " + e.getValue() + " resolved dependent(s)");
                    api.getEntity(e.getKey()).value().ifPresent(v -> evidence.add(cite(v)));
                });
        return new AgentAnswer("What packages or components are most central?",
                confirmed.isEmpty() ? "No component has resolved incoming usage yet."
                        : "Most depended-on components, by resolved incoming edges: " + firstToken(confirmed) + ".",
                confirmed, List.of(), evidence, confidence(confirmed.size(), 0), List.of(),
                List.of("Centrality counts resolved edges only; unresolved references are excluded "
                        + "and reported separately"),
                List.of("atlas tool calculate_change_impact --id <component-id>"));
    }

    AgentAnswer whatDataStoresExist() {
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        for (Views.EntityView table : api.searchEntities("", "DATABASE_OBJECT", null, CANDIDATE_CAP).value()) {
            List<Views.NeighborView> touches = api.getDependents(table.stableId(), 20).value();
            long writers = touches.stream().filter(t -> t.edge().kind().equals("WRITES_TO")).count();
            long readers = touches.stream().filter(t -> t.edge().kind().equals("READS_FROM")).count();
            confirmed.add("table '" + table.name() + "' - " + writers + " writer(s), " + readers
                    + " reader(s), mapped from "
                    + touches.stream().filter(t -> t.edge().kind().equals("MAPS_TO"))
                    .map(t -> t.entity().qualifiedName()).sorted().findFirst().orElse("(no entity mapping)"));
            evidence.add(cite(table));
            touches.stream().limit(5).forEach(t ->
                    evidence.add(new AgentAnswer.Citation(t.entity().stableId(), t.edge().evidence())));
        }
        for (Views.EntityView v : api.searchEntities("", "VARIABLE", "ada", CANDIDATE_CAP).value()) {
            int touches = api.getDependents(v.stableId(), 1).totalMatches();
            confirmed.add("Ada package state '" + v.qualifiedName() + "' - " + touches + " accessor(s)");
            evidence.add(cite(v));
        }
        return new AgentAnswer("What data stores exist?",
                confirmed.isEmpty() ? "No database tables or package state were detected."
                        : confirmed.size() + " data store(s) detected (tables and package state).",
                confirmed, List.of(), evidence, confidence(confirmed.size(), 0), List.of(),
                List.of("Tables come from parsed DDL and JPA mappings; a table shown without a "
                        + "declaration is inferred from JPA naming, not declared by any schema here",
                        "JDBC/raw SQL, dynamic SQL and Ada database bindings are not parsed yet"),
                List.of("atlas lineage sql:table:<name> --upstream",
                        "atlas lineage ada:variable:<Package.Var> --upstream"));
    }

    AgentAnswer whatExternalSystemsExist() {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();

        for (Views.EntityView io : api.getDataSources().value()) {
            confirmed.add("External input: " + io.name());
            evidence.add(cite(io));
        }
        for (Views.EntityView io : api.getDataSinks().value()) {
            confirmed.add("External output: " + io.name());
            evidence.add(cite(io));
        }
        // Unresolved dotted/qualified targets suggest systems outside the repository.
        Set<String> externals = new LinkedHashSet<>();
        for (Views.UnresolvedReference u : api.getUnresolvedReferences(200).value()) {
            String target = u.targetName();
            int cut = target.indexOf('#') >= 0 ? target.indexOf('#') : target.indexOf('.');
            if (cut > 0) {
                externals.add(target.substring(0, cut));
            }
        }
        externals.stream().sorted().limit(10).forEach(name ->
                inferred.add("'" + name + "' is referenced but not part of this repository "
                        + "- possibly an external system or library (inferred)"));
        return new AgentAnswer("What external systems exist?",
                confirmed.isEmpty() && inferred.isEmpty()
                        ? "No external interfaces were detected."
                        : confirmed.size() + " confirmed external interface(s); "
                        + inferred.size() + " possible external dependency(ies) inferred from unresolved references.",
                confirmed, inferred, evidence, confidence(confirmed.size(), inferred.size()),
                inferred.isEmpty() ? List.of()
                        : List.of("Which of the unresolved targets are real external systems versus libraries?"),
                List.of("Unresolved targets are candidates, not confirmed integrations"),
                List.of("atlas tool get_unresolved_references"));
    }

    AgentAnswer whatShouldIReadFirst() {
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        endpoints().stream().limit(2).forEach(e -> {
            String handler = e.attributes().getOrDefault("handler", "");
            inferred.add("Read the handler of " + e.name() + " (" + handler + ") - it shows the request flow (inferred recommendation)");
            evidence.add(cite(e));
        });
        for (Views.EntityView m : transformationMethods()) {
            inferred.add("Read " + m.qualifiedName() + " - a detected data transformation (inferred recommendation)");
            evidence.add(cite(m));
        }
        if (inferred.isEmpty()) {
            inferred.add("Read the largest package's externally exposed members first (inferred recommendation)");
        }
        return new AgentAnswer("What should I read first?",
                "Reading order recommendation: entry-point handlers, then the transformation methods they call, "
                        + "then the persistence layer.",
                List.of(), inferred, evidence,
                "Medium - recommendations are inferred from structure, not measured comprehension",
                List.of(), List.of("Reading order is heuristic; domain knowledge may suggest otherwise"),
                List.of("atlas summarize --id <method-or-component-id>"));
    }

    AgentAnswer whatCouldNotBeAnalyzed() {
        Views.RepositorySummaryView summary = api.getRepositorySummary().value();
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        confirmed.add(summary.unresolvedReferences() + " reference(s) could not be resolved to a target");
        confirmed.add(summary.diagnostics() + " scan diagnostic(s) recorded");
        api.getUnresolvedReferences(5).value().forEach(u -> {
            confirmed.add("unresolved: " + u.fromId() + " -> '" + u.targetName() + "'");
            evidence.add(new AgentAnswer.Citation(u.fromId(), u.location()));
        });
        // Build membership is answerable now; when a repository declares no build
        // files, that absence is itself a recorded fact worth surfacing.
        ToolResult<?> build = api.getBuildMembership(null);
        List<String> limitations = new ArrayList<>();
        if (!build.note().isBlank()) {
            limitations.add(build.note());
        }
        limitations.add("Reflection, dynamic SQL and runtime configuration are invisible to static analysis");
        return new AgentAnswer("What parts of the repository could not be analyzed?",
                "Unresolved references and analysis gaps are listed below - "
                        + "treat absent paths as unknown, not absent.",
                confirmed, List.of(), evidence,
                "High - these are the platform's own recorded gaps",
                List.of("Do the unresolved targets hide additional data flows?"),
                limitations,
                List.of("atlas tool get_unresolved_references --limit 200",
                        "atlas tool get_diagnostics"));
    }

    // ---- shared lookups ----

    private List<Views.EntityView> endpoints() {
        return api.searchEntities("", "ENDPOINT", null, CANDIDATE_CAP).value();
    }

    /** The handler method of an endpoint, read from its INVOKES edge in the graph. */
    private String handlerOf(Views.EntityView endpoint) {
        return api.getCallees(endpoint.stableId(), 5).value().stream()
                .filter(n -> n.edge().kind().equals("INVOKES"))
                .map(n -> n.entity().qualifiedName())
                .sorted().findFirst().orElse("(handler not resolved)");
    }

    private List<Views.EntityView> transformationMethods() {
        List<Views.EntityView> out = new ArrayList<>();
        for (String kind : List.of("METHOD", "FUNCTION")) {
            for (Views.EntityView m : api.searchEntities("", kind, null, CANDIDATE_CAP).value()) {
                if ("true".equals(m.attributes().get("transformation"))) {
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparing(Views.EntityView::stableId));
        return out.stream().limit(3).toList();
    }

    private static AgentAnswer.Citation cite(Views.EntityView e) {
        return new AgentAnswer.Citation(e.stableId(), e.location());
    }

    private static String confidence(int confirmedCount, int inferredCount) {
        if (confirmedCount > 0 && inferredCount == 0) {
            return "High - grounded in " + confirmedCount + " discovered/resolved fact(s)";
        }
        if (confirmedCount > 0) {
            return "Medium - " + confirmedCount + " confirmed fact(s) plus "
                    + inferredCount + " inferred finding(s)";
        }
        if (inferredCount > 0) {
            return "Low - inferred findings only";
        }
        return "High - the absence itself is a recorded fact within analyzed evidence";
    }

    private static String firstToken(List<String> lines) {
        String first = lines.get(0);
        int cut = first.indexOf(" contains ");
        if (cut < 0) {
            cut = first.indexOf(" has ");
        }
        return cut > 0 ? first.substring(0, cut) : first;
    }
}
