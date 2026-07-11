package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadCodeDetectorTest {

    private Entity method(String qn, String visibility, boolean exposed) {
        return Entity.builder(EntityKind.METHOD, qn.substring(qn.indexOf('#') + 1))
                .qualifiedName(qn).language("java")
                .location(SourceLocation.of("A.java", 1, 2))
                .attribute(Entity.Attributes.VISIBILITY, visibility)
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, exposed)
                .build();
    }

    @Test
    void flagsUncalledPrivateMethodWithHighConfidence() {
        SoftwareModel model = new SoftwareModel();
        Entity used = method("A#used", "private", false);
        Entity unused = method("A#unused", "private", false);
        model.addEntity(used);
        model.addEntity(unused);
        // Something calls 'used' (resolved), nothing calls 'unused'.
        model.addRelationship(Relationship.builder(RelationshipKind.CALLS, "caller", used.id()).build());

        List<DeadCodeCandidate> dead = new DeadCodeDetector().detect(model);
        assertEquals(1, dead.size());
        assertEquals("A#unused", dead.get(0).qualifiedName());
        assertTrue(dead.get(0).confidence() >= 90, "uncalled private method should be high confidence");
        assertTrue(dead.get(0).confidence() < 100, "must never be an absolute claim");
    }

    @Test
    void exposedMethodsAreNeverDead() {
        SoftwareModel model = new SoftwareModel();
        model.addEntity(method("A#handler", "public", true));
        assertTrue(new DeadCodeDetector().detect(model).isEmpty());
    }

    @Test
    void unresolvedCallWithSameNameLowersConfidence() {
        SoftwareModel model = new SoftwareModel();
        Entity unused = method("A#process", "private", false);
        model.addEntity(unused);
        // An unresolved call to some 'process' elsewhere: might reach this one.
        model.addRelationship(Relationship.builder(RelationshipKind.CALLS, "x", "process")
                .resolved(false).attribute("callName", "process").build());

        List<DeadCodeCandidate> dead = new DeadCodeDetector().detect(model);
        assertEquals(1, dead.size());
        assertTrue(dead.get(0).confidence() <= 70, "ambiguity must reduce confidence");
        assertFalse(dead.get(0).evidence().isEmpty());
    }
}
