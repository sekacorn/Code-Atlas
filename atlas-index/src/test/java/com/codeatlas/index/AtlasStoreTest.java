package com.codeatlas.index;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasStoreTest {

    @Test
    void roundTripsModel() {
        SoftwareModel model = new SoftwareModel();
        Entity c = Entity.builder(EntityKind.CLASS, "Service").qualifiedName("com.x.Service")
                .language("java").location(SourceLocation.of("Service.java", 1, 40))
                .attribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, 7).build();
        model.addEntity(c);
        model.addRelationship(Relationship.builder(RelationshipKind.CALLS, c.id(), "other")
                .resolved(false).build());

        try (AtlasStore store = AtlasStore.inMemory()) {
            store.saveModel(model);
            SoftwareModel loaded = store.loadModel();
            assertEquals(1, loaded.entityCount());
            assertEquals(1, loaded.relationshipCount());
            Entity back = loaded.entity(c.id()).orElseThrow();
            assertEquals("com.x.Service", back.qualifiedName());
            assertEquals(7, back.intAttribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, -1));
        }
    }

    @Test
    void detectsIncrementalChanges() {
        try (AtlasStore store = AtlasStore.inMemory()) {
            store.saveHashes(Map.of("A.java", "h1", "B.java", "h2", "C.java", "h3"));

            ChangeSet changes = store.computeChanges(Map.of(
                    "A.java", "h1",      // unchanged
                    "B.java", "h2new",   // changed
                    "D.java", "h4"));    // added; C.java removed

            assertTrue(changes.unchanged().contains("A.java"));
            assertTrue(changes.changed().contains("B.java"));
            assertTrue(changes.added().contains("D.java"));
            assertTrue(changes.removed().contains("C.java"));
            assertEquals(2, changes.reparseCount());
        }
    }
}
