package com.codeatlas.analysis.lineage;

import java.util.List;

/**
 * The report-facing view of the repository's data lineage: detected endpoints,
 * data stores, one representative downstream trace per endpoint, and coverage
 * counters. Wording is deliberately bounded — a "complete" path means complete
 * <em>within analyzed evidence</em>, never globally complete.
 */
public record LineageSummary(List<EndpointView> endpoints,
                             List<StoreView> stores,
                             List<IoView> sources,
                             List<EndpointTrace> traces,
                             List<EndpointTrace> sourceTraces,
                             Coverage coverage) {

    /** A detected HTTP endpoint. */
    public record EndpointView(String stableId, String httpMethod, String path, String handler,
                               boolean pathUnresolved, boolean validated) {
    }

    /** A detected data store (database table) and how it was named. */
    public record StoreView(String stableId, String name, String mappedFromEntity, boolean nameInferred) {
    }

    /** An input source or output sink where data enters or leaves the analyzed code. */
    public record IoView(String stableId, String name, String direction, String description) {
        public static final String SOURCE = "SOURCE";
        public static final String SINK = "SINK";
    }

    /** One representative downstream trace from an endpoint or input source. */
    public record EndpointTrace(String endpointId, List<String> steps, boolean reachesStore,
                                int gapCount, double minConfidence) {
    }

    /** Conservative lineage coverage counters. */
    public record Coverage(int endpointsDetected,
                           int endpointsWithStorePath,
                           int repositoriesDetected,
                           int repositoriesMappedToEntities,
                           int entitiesMappedToTables,
                           int resolvedEdges,
                           int inferredEdges,
                           int unresolvedEdges,
                           int completePaths,
                           int partialPaths) {
    }

    public static LineageSummary empty() {
        return new LineageSummary(List.of(), List.of(), List.of(), List.of(), List.of(),
                new Coverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
