package com.codeatlas.analysis.lineage;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@link LineageSummary} for reports: read-only over the model, one
 * bounded downstream trace per endpoint, and conservative coverage counters.
 */
public final class LineageSummarizer {

    private final LineageService service = new LineageService();

    public LineageSummary summarize(SoftwareModel model) {
        List<Entity> endpoints = model.entitiesOfKind(EntityKind.ENDPOINT).stream()
                .sorted(Comparator.comparing(Entity::id)).toList();
        List<Entity> tables = model.entitiesOfKind(EntityKind.DATABASE_OBJECT).stream()
                .sorted(Comparator.comparing(Entity::id)).toList();
        if (endpoints.isEmpty() && tables.isEmpty()) {
            return LineageSummary.empty();
        }

        List<LineageSummary.EndpointView> endpointViews = new ArrayList<>();
        for (Entity e : endpoints) {
            endpointViews.add(new LineageSummary.EndpointView(e.id(),
                    e.attribute(Entity.Attributes.HTTP_METHOD).orElse("?"),
                    e.attribute(Entity.Attributes.HTTP_PATH).orElse("?"),
                    e.attribute("handler").orElse(""),
                    e.boolAttribute(Entity.Attributes.HTTP_PATH_UNRESOLVED, false),
                    e.boolAttribute(Entity.Attributes.VALIDATED, false)));
        }

        List<LineageSummary.StoreView> storeViews = new ArrayList<>();
        for (Entity t : tables) {
            String mappedFrom = model.incoming(t.id()).stream()
                    .filter(r -> r.kind() == RelationshipKind.MAPS_TO)
                    .map(Relationship::fromId).sorted().findFirst().orElse("");
            boolean inferred = model.incoming(t.id()).stream()
                    .filter(r -> r.kind() == RelationshipKind.MAPS_TO)
                    .anyMatch(r -> r.status() == ResolutionStatus.INFERRED);
            storeViews.add(new LineageSummary.StoreView(t.id(), t.name(), mappedFrom, inferred));
        }

        List<LineageSummary.EndpointTrace> traces = new ArrayList<>();
        int endpointsWithStorePath = 0;
        int completePaths = 0;
        int partialPaths = 0;
        for (Entity e : endpoints) {
            LineageResult result = service.trace(model, LineageQuery.downstream(e.id()));
            LineageResult.Path best = pickRepresentativePath(model, result);
            boolean reachesStore = best != null && pathReachesStore(model, best);
            if (reachesStore) {
                endpointsWithStorePath++;
            }
            for (LineageResult.Path p : result.paths()) {
                if (pathReachesStore(model, p)) {
                    completePaths++;
                } else {
                    partialPaths++;
                }
            }
            traces.add(new LineageSummary.EndpointTrace(e.id(),
                    best != null ? renderSteps(model, best) : List.of(),
                    reachesStore, result.gaps().size(),
                    best != null ? best.minConfidence() : 0.0));
        }

        int repositories = 0;
        int repositoriesMapped = 0;
        for (Entity type : model.entities()) {
            if (type.boolAttribute(Entity.Attributes.SPRING_DATA_REPOSITORY, false)) {
                repositories++;
                boolean manages = model.outgoing(type.id()).stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.MANAGES);
                if (manages) {
                    repositoriesMapped++;
                }
            }
        }

        int resolvedEdges = 0;
        int inferredEdges = 0;
        int unresolvedEdges = 0;
        for (Relationship r : model.relationships()) {
            if (!LineageService.LINEAGE_KINDS.contains(r.kind())) {
                continue;
            }
            switch (r.status()) {
                case RESOLVED, DISCOVERED -> resolvedEdges++;
                case INFERRED -> inferredEdges++;
                case UNRESOLVED -> unresolvedEdges++;
            }
        }

        return new LineageSummary(endpointViews, storeViews, traces,
                new LineageSummary.Coverage(endpoints.size(), endpointsWithStorePath,
                        repositories, repositoriesMapped, countMappedEntities(model),
                        resolvedEdges, inferredEdges, unresolvedEdges, completePaths, partialPaths));
    }

    private int countMappedEntities(SoftwareModel model) {
        return (int) model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.MAPS_TO
                        && r.attributes().containsKey(EvidenceKeys.RULE_ID))
                .map(Relationship::fromId).distinct().count();
    }

    /** Prefers the first path that reaches a data store; otherwise the first path. */
    private LineageResult.Path pickRepresentativePath(SoftwareModel model, LineageResult result) {
        for (LineageResult.Path p : result.paths()) {
            if (pathReachesStore(model, p)) {
                return p;
            }
        }
        return result.paths().isEmpty() ? null : result.paths().get(0);
    }

    private boolean pathReachesStore(SoftwareModel model, LineageResult.Path path) {
        for (String nodeId : path.nodeIds()) {
            if (model.entity(nodeId).map(e -> e.kind() == EntityKind.DATABASE_OBJECT).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private List<String> renderSteps(SoftwareModel model, LineageResult.Path path) {
        List<String> steps = new ArrayList<>();
        List<String> nodes = path.nodeIds();
        for (int i = 0; i < nodes.size(); i++) {
            String label = model.entity(nodes.get(i))
                    .map(e -> e.kind() == EntityKind.ENDPOINT || e.kind() == EntityKind.DATABASE_OBJECT
                            ? (e.kind() == EntityKind.DATABASE_OBJECT ? "table: " + e.name() : e.name())
                            : e.qualifiedName())
                    .orElse(nodes.get(i));
            if (i == 0) {
                steps.add(label);
            } else {
                steps.add("-[" + path.edges().get(i - 1).kind().toLowerCase(java.util.Locale.ROOT)
                        + "]-> " + label);
            }
        }
        return steps;
    }
}
