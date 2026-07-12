package com.codeatlas.agents;

import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The Data-Lineage Investigator Agent, deterministic mode: answers where a piece
 * of data originates, what transforms it, where it is stored, where it goes and
 * who consumes it, and which parts of the path are unresolved — assembled
 * entirely from lineage traversals and neighbor queries over the read-only tool
 * API. Confirmed path steps carry per-edge evidence; ambiguous or inferred
 * segments are labelled; gaps are first-class answers, never hidden. No LLM.
 */
public final class LineageInvestigatorAgent {

    private static final Set<String> BEHAVIOUR_KINDS = Set.of("METHOD", "FUNCTION", "PROCEDURE", "CONSTRUCTOR");

    private final AtlasToolApi api;

    public LineageInvestigatorAgent(AtlasToolApi api) {
        this.api = api;
    }

    /** Investigates one entity's data lineage; the id must exist in the scan. */
    public AgentReport investigate(String stableId) {
        Views.EntityView target = api.getEntity(stableId).value()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No entity with stable id '" + stableId + "' in the latest scan"));

        LineageResult up = api.traceDataLineage(new LineageQuery(stableId,
                LineageQuery.Direction.UPSTREAM, 8, true, 0.40)).value();
        LineageResult down = api.traceDataLineage(new LineageQuery(stableId,
                LineageQuery.Direction.DOWNSTREAM, 8, true, 0.40)).value();

        List<AgentAnswer> answers = List.of(
                overview(target, up, down),
                origins(target, up),
                transformers(target, up, down),
                storage(target, up, down),
                consumers(target, up, down),
                unresolvedSegments(up, down));
        return new AgentReport(api.scanId(),
                "Data-Lineage Investigation: " + target.stableId(), answers);
    }

    // ---- the answers ----

    private AgentAnswer overview(Views.EntityView target, LineageResult up, LineageResult down) {
        LineageResult.Path best = bestOriginPath(up);
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        String pathConfidence = "Low - no connected path was found within analyzed evidence";
        if (best != null) {
            List<String> ordered = new ArrayList<>(best.nodeIds());
            java.util.Collections.reverse(ordered); // upstream walks target→origin; show origin→target
            int step = 1;
            for (String nodeId : ordered) {
                confirmed.add(step++ + ". " + labelOf(up, nodeId));
            }
            for (LineageResult.Edge e : best.edges()) {
                evidence.add(new AgentAnswer.Citation(e.fromId(),
                        e.location().isBlank() ? e.ruleId() : e.location()));
            }
            pathConfidence = confidenceBand(best.minConfidence());
        }
        int gapCount = up.gaps().size() + down.gaps().size();
        String answer = labelOf(target) + ": "
                + (best != null ? "data reaches it along the confirmed path below"
                : "no upstream origin path was found")
                + "; " + countStores(down, target) + " storage location(s) downstream, "
                + gapCount + " unresolved segment(s) across both directions.";
        return new AgentAnswer("What is the lineage of " + target.stableId() + "?",
                answer, confirmed, List.of(), evidence, pathConfidence,
                List.of(),
                List.of("A path is complete only within analyzed evidence; reflection, dynamic SQL "
                        + "and external configuration may add paths Code Atlas cannot see"),
                List.of("atlas lineage " + target.stableId() + " --both --include-inferred"));
    }

    private AgentAnswer origins(Views.EntityView target, LineageResult up) {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        for (LineageResult.Path path : up.paths()) {
            String terminalId = path.nodeIds().get(path.nodeIds().size() - 1);
            String kind = kindOf(up, terminalId);
            boolean viaInference = path.edges().stream().anyMatch(LineageResult.Edge::inferred);
            if ((kind.equals("ENDPOINT") || kind.equals("DATA_SOURCE")) && seen.add(terminalId)) {
                String line = "originates at " + labelOf(up, terminalId);
                if (viaInference) {
                    inferred.add(line + " (path includes inferred segments)");
                } else {
                    confirmed.add(line);
                }
                evidence.add(new AgentAnswer.Citation(terminalId, locationOf(up, terminalId)));
            }
        }
        // A store's writers may take their input from a data source one hop away.
        for (String nodeId : behaviourNodes(up)) {
            for (Views.NeighborView dep : api.getDependencies(nodeId, 20).value()) {
                if (dep.edge().kind().equals("READS_FROM")
                        && dep.entity().kind().equals("DATA_SOURCE")
                        && seen.add(dep.entity().stableId() + "|" + nodeId)) {
                    confirmed.add("originates at " + dep.entity().name() + ", read by "
                            + shortLabel(up, nodeId));
                    evidence.add(new AgentAnswer.Citation(dep.entity().stableId(), dep.edge().evidence()));
                }
            }
        }
        boolean empty = confirmed.isEmpty() && inferred.isEmpty();
        return new AgentAnswer("Where did this data originate?",
                empty ? "No origin was identified within analyzed evidence."
                        : (confirmed.size() + inferred.size()) + " origin(s) identified.",
                confirmed, inferred, evidence,
                empty ? "High - the absence itself is a recorded fact within analyzed evidence"
                        : confidence(confirmed.size(), inferred.size()),
                empty ? List.of("Is the data produced outside the analyzed repository?") : List.of(),
                List.of("Origins reached only through unresolved references cannot be shown"),
                List.of("atlas lineage " + target.stableId() + " --upstream"));
    }

    private AgentAnswer transformers(Views.EntityView target, LineageResult up, LineageResult down) {
        List<String> confirmed = new ArrayList<>();
        List<String> inferred = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        // CONSUMES/PRODUCES edges on the paths mark transformation steps directly;
        // their rule ids and inferred flags carry the honesty grading.
        TreeMap<String, LineageResult.Edge> byTransformer = new TreeMap<>();
        for (LineageResult.Path path : allPaths(up, down)) {
            for (LineageResult.Edge e : path.edges()) {
                if (e.kind().equals("CONSUMES") || e.kind().equals("PRODUCES")) {
                    byTransformer.putIfAbsent(e.fromId(), e);
                }
            }
        }
        byTransformer.forEach((id, edge) -> {
            if (!seen.add(id)) {
                return;
            }
            String line = "transformed by " + shortId(id) + " (rule " + edge.ruleId() + ")";
            if (edge.inferred()) {
                inferred.add(line + " (inferred)");
            } else {
                confirmed.add(line);
            }
            evidence.add(new AgentAnswer.Citation(id, edge.location()));
        });

        // A writer on the path may hand the data through a transformation it
        // invokes (e.g. State := Transform(X)) — one evidence-backed hop away.
        for (String nodeId : behaviourNodes(up)) {
            for (Views.NeighborView callee : api.getCallees(nodeId, 20).value()) {
                if ("true".equals(callee.entity().attributes().get("transformation"))
                        && seen.add(callee.entity().stableId())) {
                    String line = "transformed by " + callee.entity().qualifiedName()
                            + ", invoked by " + shortId(nodeId);
                    if (callee.edge().inferred()) {
                        inferred.add(line + " (inferred)");
                    } else {
                        confirmed.add(line);
                    }
                    evidence.add(new AgentAnswer.Citation(callee.entity().stableId(),
                            callee.edge().evidence()));
                }
            }
        }
        boolean empty = seen.isEmpty();
        return new AgentAnswer("What transforms it?",
                empty ? "No transformation step was detected on its lineage paths."
                        : seen.size() + " transformation step(s) detected.",
                confirmed, inferred, evidence,
                empty ? "High - the absence itself is a recorded fact within analyzed evidence"
                        : confidence(confirmed.size(), inferred.size()),
                List.of(),
                List.of("Transformation detection covers single-input mappings with type flow or "
                        + "mapper-style naming; other conversions may exist undetected"),
                List.of("atlas summarize <transformer-id>"));
    }

    private AgentAnswer storage(Views.EntityView target, LineageResult up, LineageResult down) {
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        if (target.kind().equals("DATABASE_OBJECT") || target.kind().equals("VARIABLE")) {
            confirmed.add("the investigated entity is itself a data store ("
                    + target.kind().toLowerCase(Locale.ROOT) + " " + target.name() + ")");
            seen.add(target.stableId());
        }
        for (LineageResult.Path path : allPaths(up, down)) {
            for (LineageResult.Edge e : path.edges()) {
                boolean storeEdge = e.kind().equals("WRITES_TO") || e.kind().equals("PERSISTS_TO")
                        || e.kind().equals("MAPS_TO");
                String toKind = kindOf(up, e.toId()).isEmpty() ? kindOf(down, e.toId()) : kindOf(up, e.toId());
                if (storeEdge && (toKind.equals("DATABASE_OBJECT") || toKind.equals("VARIABLE")
                        || toKind.equals("DATA_SINK")) && seen.add(e.toId())) {
                    confirmed.add("stored in " + labelOf(up, down, e.toId())
                            + " via " + e.kind().toLowerCase(Locale.ROOT)
                            + " from " + shortId(e.fromId()));
                    evidence.add(new AgentAnswer.Citation(e.toId(), e.location()));
                }
            }
        }
        boolean empty = confirmed.isEmpty();
        return new AgentAnswer("Where is it stored?",
                empty ? "No storage location was found on its lineage paths."
                        : seen.size() + " storage location(s) identified.",
                confirmed, List.of(), evidence,
                empty ? "High - the absence itself is a recorded fact within analyzed evidence"
                        : confidence(confirmed.size(), 0),
                empty ? List.of("Is the data persisted by code outside the analyzed repository?") : List.of(),
                List.of("Only JPA-mapped tables, Ada package state and console sinks are modeled as stores"),
                List.of("atlas tool get_database_references --id " + target.stableId()));
    }

    private AgentAnswer consumers(Views.EntityView target, LineageResult up, LineageResult down) {
        List<String> confirmed = new ArrayList<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        List<String> unresolvedQuestions = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        for (Views.NeighborView dep : api.getDependents(target.stableId(), 30).value()) {
            String kind = dep.edge().kind();
            if ((kind.equals("READS_FROM") || kind.equals("CONSUMES") || kind.equals("INVOKES")
                    || kind.equals("USES")) && seen.add(dep.entity().stableId())) {
                confirmed.add("consumed by " + dep.entity().qualifiedName()
                        + " (" + kind.toLowerCase(Locale.ROOT) + ")");
                evidence.add(new AgentAnswer.Citation(dep.entity().stableId(), dep.edge().evidence()));
            }
        }
        for (LineageResult.Path path : down.paths()) {
            String terminalId = path.nodeIds().get(path.nodeIds().size() - 1);
            if (kindOf(down, terminalId).equals("DATA_SINK") && seen.add(terminalId)) {
                confirmed.add("flows to " + labelOf(down, terminalId));
                evidence.add(new AgentAnswer.Citation(terminalId, locationOf(down, terminalId)));
            }
        }
        for (LineageResult.Gap gap : down.gaps()) {
            if (gap.kind().equals(LineageResult.Gap.EXTERNAL_CONSUMER)) {
                unresolvedQuestions.add(gap.description());
            }
        }
        boolean empty = confirmed.isEmpty() && unresolvedQuestions.isEmpty();
        return new AgentAnswer("Where does it go, and who consumes it?",
                empty ? "No consumer was identified within analyzed evidence."
                        : confirmed.size() + " consumer(s)/destination(s) identified"
                        + (unresolvedQuestions.isEmpty() ? "." : "; at least one consumer is external."),
                confirmed, List.of(), evidence,
                empty ? "High - the absence itself is a recorded fact within analyzed evidence"
                        : confidence(confirmed.size(), 0),
                unresolvedQuestions,
                List.of("External consumers of responses/outputs are outside the analyzed repository"),
                List.of("atlas tool get_dependents --id " + target.stableId()));
    }

    private AgentAnswer unresolvedSegments(LineageResult up, LineageResult down) {
        Set<String> lines = new LinkedHashSet<>();
        List<AgentAnswer.Citation> evidence = new ArrayList<>();
        for (LineageResult result : List.of(up, down)) {
            for (LineageResult.Gap gap : result.gaps()) {
                if (lines.add("[" + gap.kind() + "] " + gap.description())) {
                    evidence.add(new AgentAnswer.Citation(gap.atId(), ""));
                }
            }
        }
        boolean none = lines.isEmpty();
        return new AgentAnswer("Which parts of the path are unresolved?",
                none ? "No unresolved segment was found within the selected scope."
                        : lines.size() + " unresolved segment(s) - listed below, never dropped.",
                List.copyOf(lines), List.of(), evidence,
                "High - these are the platform's own recorded gaps",
                List.of(),
                List.of("Runtime reflection, framework proxies, dynamic SQL and external "
                        + "configuration may introduce additional paths"),
                List.of("atlas tool get_unresolved_references"));
    }

    // ---- helpers ----

    /** The best origin path: an upstream path ending at an endpoint or data source. */
    private LineageResult.Path bestOriginPath(LineageResult up) {
        LineageResult.Path best = null;
        for (LineageResult.Path path : up.paths()) {
            String terminalKind = kindOf(up, path.nodeIds().get(path.nodeIds().size() - 1));
            boolean origin = terminalKind.equals("ENDPOINT") || terminalKind.equals("DATA_SOURCE");
            if (origin && (best == null || path.nodeIds().size() > best.nodeIds().size())) {
                best = path;
            }
        }
        if (best == null && !up.paths().isEmpty()) {
            best = up.paths().get(0);
        }
        return best;
    }

    private static List<LineageResult.Path> allPaths(LineageResult up, LineageResult down) {
        List<LineageResult.Path> all = new ArrayList<>(up.paths());
        all.addAll(down.paths());
        return all;
    }

    private List<String> behaviourNodes(LineageResult result) {
        List<String> out = new ArrayList<>();
        for (LineageResult.Node n : result.nodes()) {
            if (BEHAVIOUR_KINDS.contains(n.kind())) {
                out.add(n.id());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static String kindOf(LineageResult result, String nodeId) {
        return result.nodes().stream().filter(n -> n.id().equals(nodeId))
                .map(LineageResult.Node::kind).findFirst().orElse("");
    }

    private static String labelOf(LineageResult result, String nodeId) {
        return result.nodes().stream().filter(n -> n.id().equals(nodeId))
                .map(n -> n.kind().equals("DATABASE_OBJECT") ? "table: " + n.label() : n.label())
                .findFirst().orElse(nodeId);
    }

    private static String labelOf(LineageResult up, LineageResult down, String nodeId) {
        String fromUp = labelOf(up, nodeId);
        return fromUp.equals(nodeId) ? labelOf(down, nodeId) : fromUp;
    }

    private static String labelOf(Views.EntityView e) {
        return e.kind().equals("ENDPOINT") || e.kind().equals("DATABASE_OBJECT")
                ? e.name() : e.qualifiedName();
    }

    private static String shortLabel(LineageResult result, String nodeId) {
        return labelOf(result, nodeId);
    }

    private static String shortId(String stableId) {
        int colon = stableId.lastIndexOf(':');
        return colon >= 0 ? stableId.substring(colon + 1) : stableId;
    }

    private static String locationOf(LineageResult result, String nodeId) {
        return result.nodes().stream().filter(n -> n.id().equals(nodeId))
                .map(LineageResult.Node::location).findFirst().orElse("");
    }

    private static int countStores(LineageResult down, Views.EntityView target) {
        Set<String> stores = new TreeSet<>();
        if (target.kind().equals("DATABASE_OBJECT") || target.kind().equals("VARIABLE")) {
            stores.add(target.stableId());
        }
        for (LineageResult.Node n : down.nodes()) {
            if (n.kind().equals("DATABASE_OBJECT") || n.kind().equals("VARIABLE")) {
                stores.add(n.id());
            }
        }
        return stores.size();
    }

    private static String confidenceBand(double minConfidence) {
        String band = minConfidence >= 0.85 ? "High" : minConfidence >= 0.60 ? "Medium" : "Low";
        return band + String.format(Locale.ROOT,
                " - the weakest edge on the confirmed path carries confidence %.2f", minConfidence);
    }

    private static String confidence(int confirmedCount, int inferredCount) {
        if (confirmedCount > 0 && inferredCount == 0) {
            return "High - grounded in " + confirmedCount + " discovered/resolved fact(s)";
        }
        if (confirmedCount > 0) {
            return "Medium - " + confirmedCount + " confirmed fact(s) plus "
                    + inferredCount + " inferred finding(s)";
        }
        return "Low - inferred findings only";
    }
}
