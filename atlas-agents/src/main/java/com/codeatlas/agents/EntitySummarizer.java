package com.codeatlas.agents;

import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Deterministic entity summaries (methods/subprograms and components) assembled
 * from tool-API results and rendered in the standard agent answer structure.
 * Facts (parameters, calls, reads, writes, contracts) are confirmed and cited;
 * responsibility statements and lineage roles derived from naming or structure
 * are explicitly labelled inferred. No AI is involved.
 */
public final class EntitySummarizer {

    private static final Set<String> METHOD_KINDS = Set.of("METHOD", "CONSTRUCTOR", "FUNCTION", "PROCEDURE");
    private static final Set<String> COMPONENT_KINDS =
            Set.of("PACKAGE", "CLASS", "INTERFACE", "ENUM", "RECORD", "TYPE");

    private final AtlasToolApi api;

    public EntitySummarizer(AtlasToolApi api) {
        this.api = api;
    }

    /** Summarizes any supported entity; throws for ids that are not in the scan. */
    public AgentAnswer summarize(String stableId) {
        Views.EntityView entity = api.getEntity(stableId).value()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No entity with stable id '" + stableId + "' in the latest scan"));
        if (METHOD_KINDS.contains(entity.kind())) {
            return summarizeMethod(entity);
        }
        if (COMPONENT_KINDS.contains(entity.kind())) {
            return summarizeComponent(entity);
        }
        throw new IllegalArgumentException("Summaries cover methods/subprograms and components; '"
                + entity.kind() + "' is not supported yet");
    }

    // ---- method / subprogram ----

    private AgentAnswer summarizeMethod(Views.EntityView m) {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        evidence.add(new AgentAnswer.Citation(m.stableId(), m.location()));

        String params = m.attributes().getOrDefault("paramTypes", "");
        confirmed.add(params.isEmpty() ? "takes no parameters" : "parameters: " + params);
        String returns = m.attributes().getOrDefault("returnTypeNormalized",
                m.attributes().getOrDefault("returnType", ""));
        confirmed.add(returns.isEmpty() || returns.equals("void")
                ? "returns nothing" : "returns: " + returns);
        String complexity = m.attributes().get("cyclomaticComplexity");
        if (complexity != null) {
            confirmed.add("cyclomatic complexity: " + complexity);
        }

        List<Views.NeighborView> callees = api.getCallees(m.stableId(), 20).value();
        for (Views.NeighborView c : callees) {
            confirmed.add("calls " + c.entity().qualifiedName());
            evidence.add(new AgentAnswer.Citation(c.entity().stableId(), c.edge().evidence()));
        }

        List<String> sideEffects = new ArrayList<>();
        for (Views.NeighborView d : api.getDependencies(m.stableId(), 50).value()) {
            String kind = d.edge().kind();
            String what = d.entity().kind() + " " + d.entity().name();
            switch (kind) {
                case "WRITES_TO" -> sideEffects.add("writes to " + what);
                case "READS_FROM" -> confirmed.add("reads from " + what);
                case "CONSUMES" -> confirmed.add("consumes " + d.entity().qualifiedName());
                case "PRODUCES" -> confirmed.add("produces " + d.entity().qualifiedName());
                default -> {
                }
            }
            if (kind.equals("WRITES_TO") || kind.equals("READS_FROM")
                    || kind.equals("CONSUMES") || kind.equals("PRODUCES")) {
                evidence.add(new AgentAnswer.Citation(d.entity().stableId(), d.edge().evidence()));
            }
        }
        if (sideEffects.isEmpty()) {
            confirmed.add("no write side effects detected within analyzed evidence");
        } else {
            confirmed.addAll(sideEffects);
        }

        String pre = m.attributes().get("sparkPrecondition");
        if (pre != null) {
            confirmed.add("SPARK precondition: " + pre);
        }
        String post = m.attributes().get("sparkPostcondition");
        if (post != null) {
            confirmed.add("SPARK postcondition: " + post);
        }

        // Lineage role: handler / transformation / persistence - labelled per source.
        for (Views.NeighborView caller : api.getCallers(m.stableId(), 10).value()) {
            if (caller.entity().kind().equals("ENDPOINT")) {
                confirmed.add("handles " + caller.entity().name());
                evidence.add(new AgentAnswer.Citation(caller.entity().stableId(), caller.edge().evidence()));
            }
        }
        if ("true".equals(m.attributes().get("transformation"))) {
            inferred.add("acts as a data transformation (detected from type flow"
                    + " and naming - see its consumes/produces facts)");
        }

        String answer = m.qualifiedName() + ": "
                + (params.isEmpty() ? "no inputs" : "inputs (" + params + ")")
                + (returns.isEmpty() || returns.equals("void") ? "" : " -> " + returns)
                + "; " + callees.size() + " call(s), "
                + sideEffects.size() + " write side effect(s).";
        return new AgentAnswer("Summarize " + m.stableId(), answer, confirmed, inferred, evidence,
                confirmed.isEmpty() ? "Low - inferred only"
                        : "High - grounded in " + confirmed.size() + " extracted fact(s)",
                List.of(),
                List.of("Exception/raise evidence is not extracted yet",
                        "Calls through local variables or chained receivers are not followed"),
                List.of("atlas tool calculate_change_impact --id " + m.stableId(),
                        "atlas lineage " + m.stableId() + " --downstream"));
    }

    // ---- component ----

    private AgentAnswer summarizeComponent(Views.EntityView c) {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        evidence.add(new AgentAnswer.Citation(c.stableId(), c.location()));

        var members = api.getMembers(c.stableId(), 200);
        confirmed.add("contains " + members.totalMatches() + " member(s)");
        members.value().stream().limit(8).forEach(mem -> {
            confirmed.add("member: " + mem.entity().kind() + " " + mem.entity().name());
            evidence.add(new AgentAnswer.Citation(mem.entity().stableId(), mem.entity().location()));
        });

        var dependencies = api.getDependencies(c.stableId(), 20).value();
        var consumers = api.getDependents(c.stableId(), 20).value();
        dependencies.stream().limit(8).forEach(d ->
                confirmed.add("depends on " + d.entity().qualifiedName() + " (" + d.edge().kind().toLowerCase() + ")"));
        consumers.stream().limit(8).forEach(d ->
                confirmed.add("used by " + d.entity().qualifiedName() + " (" + d.edge().kind().toLowerCase() + ")"));
        dependencies.stream().filter(d -> d.entity().kind().equals("DATABASE_OBJECT")).forEach(d -> {
            confirmed.add("touches data store '" + d.entity().name() + "' (" + d.edge().kind().toLowerCase() + ")");
            evidence.add(new AgentAnswer.Citation(d.entity().stableId(), d.edge().evidence()));
        });

        int maxComplexity = members.value().stream()
                .mapToInt(mem -> parseIntOr(mem.entity().attributes().get("cyclomaticComplexity")))
                .max().orElse(0);
        if (maxComplexity > 0) {
            confirmed.add("highest member cyclomatic complexity: " + maxComplexity);
        }
        api.findDeadCodeCandidates(200).value().stream()
                .filter(dc -> dc.stableId().startsWith(prefixOf(c)))
                .sorted(Comparator.comparing(dc -> dc.stableId()))
                .limit(5)
                .forEach(dc -> confirmed.add("risk: probable dead code " + dc.qualifiedName()
                        + " (confidence " + dc.confidence() + "%)"));

        String responsibility = responsibilityOf(c, members.value());
        inferred.add("responsibility: " + responsibility + " (inferred from structure and annotations)");

        String answer = c.qualifiedName() + " - " + responsibility + "; "
                + members.totalMatches() + " member(s), " + dependencies.size()
                + " dependency(ies), " + consumers.size() + " consumer(s).";
        return new AgentAnswer("Summarize " + c.stableId(), answer, confirmed, inferred, evidence,
                "Medium - structural facts are confirmed; the responsibility statement is inferred",
                List.of(),
                List.of("Responsibility is derived from roles/annotations, not from documentation or runtime behavior"),
                List.of("atlas tool get_members --id " + c.stableId(),
                        "atlas tool calculate_change_impact --id " + c.stableId()));
    }

    private String responsibilityOf(Views.EntityView c, List<Views.NeighborView> members) {
        String role = c.attributes().getOrDefault("role", "");
        switch (role) {
            case "controller":
                return "handles HTTP requests";
            case "service":
                return "business/service logic";
            case "repository":
                return "data access";
            case "mapper-interface":
                return "data mapping";
            case "dto-request":
                return "request data transfer object";
            case "dto-response":
                return "response data transfer object";
            default:
                break;
        }
        if ("true".equals(c.attributes().get("jpaEntity"))) {
            return "persistent entity";
        }
        boolean hasTransformation = members.stream()
                .anyMatch(m -> "true".equals(m.entity().attributes().get("transformation")));
        if (hasTransformation) {
            return "data transformation";
        }
        boolean hasState = members.stream().anyMatch(m -> m.entity().kind().equals("VARIABLE"));
        if (hasState) {
            return "holds package state and its operations";
        }
        return "responsibility not determinable from structure";
    }

    private static String prefixOf(Views.EntityView c) {
        // Members of com.x.Foo have ids like java:method:com.x.Foo#...
        int colon = c.stableId().lastIndexOf(':');
        return c.stableId().substring(0, colon + 1).replace(":type:", ":method:");
    }

    private static int parseIntOr(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
