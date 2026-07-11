package com.codeatlas.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the derivation rules for {@link ResolutionStatus} so reports and future
 * agents always get an honest, backward-compatible status from existing edges.
 */
class ResolutionStatusTest {

    @Test
    void structuralContainmentIsDiscovered() {
        Relationship r = Relationship.builder(RelationshipKind.CONTAINS, "a", "b").build();
        assertEquals(ResolutionStatus.DISCOVERED, r.status());
        assertEquals(true, r.resolved(), "existing resolved() semantics preserved");
    }

    @Test
    void resolvedReferenceIsResolved() {
        Relationship r = Relationship.builder(RelationshipKind.CALLS, "a", "b").resolved(true).build();
        assertEquals(ResolutionStatus.RESOLVED, r.status());
    }

    @Test
    void unresolvedReferenceIsUnresolved() {
        Relationship r = Relationship.builder(RelationshipKind.CALLS, "a", "foo")
                .resolved(false).build();
        assertEquals(ResolutionStatus.UNRESOLVED, r.status());
    }

    @Test
    void explicitStatusOverridesDerivation() {
        Relationship r = Relationship.builder(RelationshipKind.REFERENCES, "a", "b")
                .status(ResolutionStatus.INFERRED).build();
        assertEquals(ResolutionStatus.INFERRED, r.status());
    }
}
