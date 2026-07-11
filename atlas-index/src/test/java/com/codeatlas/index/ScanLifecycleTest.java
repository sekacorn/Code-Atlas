package com.codeatlas.index;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanLifecycleTest {

    private SoftwareModel modelWith(String className) {
        SoftwareModel model = new SoftwareModel();
        Entity c = Entity.builder(EntityKind.CLASS, className).qualifiedName("com.x." + className)
                .language("java").location(SourceLocation.of(className + ".java", 1, 10)).build();
        model.addEntity(c);
        return model;
    }

    @Test
    void completedScanBecomesCurrentAndHistoryIsKept() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            long s1 = store.beginScan("scan-aaa", "/repo", 1);
            store.persistCompletedScan(s1, modelWith("A"), Map.of("A.java", "h1"), List.of(), Set.of("A.java"));

            long s2 = store.beginScan("scan-bbb", "/repo", 1);
            store.persistCompletedScan(s2, modelWith("B"), Map.of("B.java", "h2"), List.of(), Set.of("B.java"));

            ScanRecord latest = store.latestCompletedScan().orElseThrow();
            assertEquals("scan-bbb", latest.scanKey());
            assertEquals(2, store.scanHistory().size(), "every run stays distinguishable");
        }
    }

    @Test
    void failedScanNeverReplacesThePreviousCompletedModel() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            long s1 = store.beginScan("scan-good", "/repo", 1);
            store.persistCompletedScan(s1, modelWith("Keep"), Map.of("Keep.java", "h1"), List.of(), Set.of("Keep.java"));

            long s2 = store.beginScan("scan-bad", "/repo", 1);
            store.markScanFailed(s2);

            ScanRecord latest = store.latestCompletedScan().orElseThrow();
            assertEquals("scan-good", latest.scanKey(), "failed run must not become current");
            SoftwareModel loaded = store.loadModel();
            assertTrue(loaded.entity("java:type:com.x.Keep").isPresent(),
                    "previous completed model must remain readable");
            assertEquals(ScanRecord.FAILED, store.scanHistory().get(0).status());
        }
    }

    @Test
    void parseCacheRoundTripsFactsExactly() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            Entity method = Entity.builder(EntityKind.METHOD, "run").qualifiedName("com.x.A#run()")
                    .language("java").location(new SourceLocation("A.java", 3, 7, 5, 6))
                    .attribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, 4)
                    .attribute(Entity.Attributes.VISIBILITY, "private")
                    .build();
            Relationship call = Relationship.builder(RelationshipKind.CALLS, method.id(), "helper")
                    .resolved(false)
                    .location(SourceLocation.of("A.java", 5, 5))
                    .attribute("callName", "helper")
                    .attribute("argCount", "2")
                    .build();
            CachedFileMeta meta = new CachedFileMeta("A.java", "hash-1", "java", "1.0.0",
                    CachedFileMeta.ANALYZED, 10, 2, 1);

            long s1 = store.beginScan("scan-c", "/repo", 1);
            store.persistCompletedScan(s1, modelWith("A"), Map.of("A.java", "hash-1"),
                    List.of(new CacheEntry(meta, List.of(method), List.of(call))), Set.of("A.java"));

            CachedFileMeta loadedMeta = store.cacheIndex().get("A.java");
            assertEquals(meta, loadedMeta, "cache header round-trips");

            CacheEntry loaded = store.loadCachedFacts(loadedMeta);
            assertEquals(1, loaded.entities().size());
            Entity e = loaded.entities().get(0);
            assertEquals(method.id(), e.id());
            assertEquals(method.attributes(), e.attributes(), "entity attributes round-trip");
            assertEquals(method.location(), e.location(), "entity location (incl. columns) round-trips");

            assertEquals(1, loaded.relationships().size());
            Relationship r = loaded.relationships().get(0);
            assertEquals(call.fromId(), r.fromId());
            assertEquals(call.toId(), r.toId());
            assertEquals(ResolutionStatus.UNRESOLVED, r.status());
            assertEquals(call.attributes(), r.attributes(), "relationship attributes round-trip");
        }
    }

    @Test
    void cacheRowsForDeletedFilesArePruned() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            CachedFileMeta a = new CachedFileMeta("A.java", "h1", "java", "1.0.0", CachedFileMeta.ANALYZED, 1, 0, 0);
            CachedFileMeta b = new CachedFileMeta("B.java", "h2", "java", "1.0.0", CachedFileMeta.ANALYZED, 1, 0, 0);
            long s1 = store.beginScan("scan-1", "/repo", 2);
            store.persistCompletedScan(s1, modelWith("A"), Map.of("A.java", "h1", "B.java", "h2"),
                    List.of(new CacheEntry(a, List.of(), List.of()), new CacheEntry(b, List.of(), List.of())),
                    Set.of("A.java", "B.java"));

            // Next scan: B.java was deleted.
            long s2 = store.beginScan("scan-2", "/repo", 1);
            store.persistCompletedScan(s2, modelWith("A"), Map.of("A.java", "h1"), List.of(), Set.of("A.java"));

            assertTrue(store.cacheIndex().containsKey("A.java"));
            assertTrue(!store.cacheIndex().containsKey("B.java"), "stale cache rows must be pruned");
        }
    }

    @Test
    void relationshipMetadataSurvivesModelPersistence() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            SoftwareModel model = modelWith("A");
            model.addRelationship(Relationship.builder(RelationshipKind.CALLS, "x", "y")
                    .resolved(true).status(ResolutionStatus.INFERRED)
                    .location(SourceLocation.of("A.java", 4, 4))
                    .attribute("ruleId", "TEST-RULE-001")
                    .attribute("confidence", "0.75")
                    .build());
            store.saveModel(model);

            SoftwareModel loaded = store.loadModel();
            Relationship r = loaded.relationships().stream()
                    .filter(x -> x.kind() == RelationshipKind.CALLS).findFirst().orElseThrow();
            assertEquals(ResolutionStatus.INFERRED, r.status(), "explicit status survives persistence");
            assertEquals("TEST-RULE-001", r.attributes().get("ruleId"));
            assertEquals("0.75", r.attributes().get("confidence"));
            assertEquals("A.java:4", r.location().orElseThrow().toString());
        }
    }
}
