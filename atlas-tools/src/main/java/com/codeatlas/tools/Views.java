package com.codeatlas.tools;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.SourceLocation;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable, agent-facing views of model objects. Every view uses stable ids and
 * carries evidence (locations, rule ids, confidence, resolution status) so agent
 * answers can cite their sources. Attribute maps are sorted for deterministic
 * rendering.
 */
public final class Views {

    private Views() {
    }

    /** An entity: identity, classification and evidence. */
    public record EntityView(String stableId, String kind, String name, String qualifiedName,
                             String language, String location, Map<String, String> attributes) {

        public EntityView {
            attributes = java.util.Collections.unmodifiableMap(new TreeMap<>(attributes));
        }

        static EntityView of(Entity e) {
            return new EntityView(e.id(), e.kind().name(), e.name(), e.qualifiedName(), e.language(),
                    e.location().map(SourceLocation::toString).orElse(""),
                    new TreeMap<>(e.attributes()));
        }
    }

    /** An edge with its full evidence metadata. */
    public record EdgeView(String fromId, String toId, String kind, String ruleId, String confidence,
                           String status, boolean inferred, boolean ambiguous, String evidence) {

        static EdgeView of(Relationship r) {
            return new EdgeView(r.fromId(), r.toId(), r.kind().name(),
                    r.attributes().getOrDefault(EvidenceKeys.RULE_ID, ""),
                    r.attributes().getOrDefault(EvidenceKeys.CONFIDENCE, ""),
                    r.status().name(),
                    "true".equals(r.attributes().get(EvidenceKeys.INFERRED)),
                    "true".equals(r.attributes().get(EvidenceKeys.AMBIGUOUS)),
                    r.location().map(SourceLocation::toString).orElse(""));
        }
    }

    /** A neighboring entity together with the edge that connects it. */
    public record NeighborView(EntityView entity, EdgeView edge) {
    }

    /** Source evidence for one entity, including Ada spec/body split when present. */
    public record EvidenceView(String stableId, String location, String specLocation,
                               String bodyLocation, String fileHash, String note) {
    }

    /** A detected reference whose target could not be identified — kept visible. */
    public record UnresolvedReference(String fromId, String targetName, String kind, String location) {
    }

    /** Deterministic change-impact assessment for one entity. */
    public record ImpactView(String targetId,
                             List<NeighborView> directDependents,
                             List<EntityView> indirectDependents,
                             List<NeighborView> databaseImpact,
                             List<String> downstreamLineage,
                             List<String> unresolvedRisks,
                             String limitations) {
        public ImpactView {
            directDependents = List.copyOf(directDependents);
            indirectDependents = List.copyOf(indirectDependents);
            databaseImpact = List.copyOf(databaseImpact);
            downstreamLineage = List.copyOf(downstreamLineage);
            unresolvedRisks = List.copyOf(unresolvedRisks);
        }
    }

    /** Resolved / unresolved / ambiguous cross-reference counts (structural
     *  {@code CONTAINS} edges excluded), for deriving scan health from a reused scan. */
    public record ReferenceCounts(int resolved, int unresolved, int ambiguous) {
        public int total() {
            return resolved + unresolved + ambiguous;
        }

        public int resolutionRatePercent() {
            return total() == 0 ? 100 : (int) Math.round(100.0 * resolved / total());
        }
    }

    /** Headline repository facts for orientation. */
    public record RepositorySummaryView(int totalFiles,
                                        long totalLines,
                                        long codeLines,
                                        Map<String, Integer> filesByLanguage,
                                        Map<String, Integer> entityCounts,
                                        List<String> endpoints,
                                        List<String> dataStores,
                                        List<String> dataSources,
                                        List<String> dataSinks,
                                        int deadCodeCandidates,
                                        int complexityHotspots,
                                        int unresolvedReferences,
                                        int diagnostics) {
        public RepositorySummaryView {
            filesByLanguage = java.util.Collections.unmodifiableMap(new TreeMap<>(filesByLanguage));
            entityCounts = java.util.Collections.unmodifiableMap(new TreeMap<>(entityCounts));
            endpoints = List.copyOf(endpoints);
            dataStores = List.copyOf(dataStores);
            dataSources = List.copyOf(dataSources);
            dataSinks = List.copyOf(dataSinks);
        }
    }
}
