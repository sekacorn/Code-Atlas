package com.codeatlas.analysis.lineage;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Traversal semantics on a hand-built model: cycles, depth, filters, determinism. */
class LineageServiceTest {

    private final LineageService service = new LineageService();

    private static Entity method(String qn) {
        return Entity.builder(EntityKind.METHOD, qn.substring(qn.indexOf('#') + 1))
                .qualifiedName(qn).language("java").build();
    }

    private static Relationship invokes(String from, String to, double confidence, ResolutionStatus status) {
        return Relationship.builder(RelationshipKind.INVOKES, from, to)
                .resolved(status != ResolutionStatus.UNRESOLVED)
                .status(status)
                .attribute(EvidenceKeys.CONFIDENCE, String.format(java.util.Locale.ROOT, "%.2f", confidence))
                .build();
    }

    @Test
    void cyclesAreCutNotFollowedForever() {
        SoftwareModel model = new SoftwareModel();
        Entity a = method("A#a()");
        Entity b = method("B#b()");
        model.addEntity(a);
        model.addEntity(b);
        model.addRelationship(invokes(a.id(), b.id(), 0.95, ResolutionStatus.RESOLVED));
        model.addRelationship(invokes(b.id(), a.id(), 0.95, ResolutionStatus.RESOLVED));

        LineageResult result = service.trace(model, LineageQuery.downstream(a.id()));
        assertTrue(result.cyclesDetected(), "the cycle must be reported");
        assertEquals(1, result.paths().size(), "the cycle must be cut into one finite path");
    }

    @Test
    void maxDepthTruncatesAndIsReported() {
        SoftwareModel model = new SoftwareModel();
        Entity first = method("C0#m()");
        model.addEntity(first);
        Entity prev = first;
        for (int i = 1; i <= 5; i++) {
            Entity next = method("C" + i + "#m()");
            model.addEntity(next);
            model.addRelationship(invokes(prev.id(), next.id(), 0.95, ResolutionStatus.RESOLVED));
            prev = next;
        }
        LineageResult result = service.trace(model,
                new LineageQuery(first.id(), LineageQuery.Direction.DOWNSTREAM, 2, true, 0.4));
        assertTrue(result.truncated(), "depth-limited traversal must say so");
        assertEquals(3, result.paths().get(0).nodeIds().size(), "path length must respect maxDepth");
    }

    @Test
    void confidenceAndInferredFiltersExcludeEdges() {
        SoftwareModel model = new SoftwareModel();
        Entity a = method("A#a()");
        Entity strong = method("B#strong()");
        Entity weak = method("C#weak()");
        Entity guessed = method("D#guessed()");
        model.addEntity(a);
        model.addEntity(strong);
        model.addEntity(weak);
        model.addEntity(guessed);
        model.addRelationship(invokes(a.id(), strong.id(), 0.95, ResolutionStatus.RESOLVED));
        model.addRelationship(invokes(a.id(), weak.id(), 0.30, ResolutionStatus.RESOLVED));
        model.addRelationship(invokes(a.id(), guessed.id(), 0.60, ResolutionStatus.INFERRED));

        LineageResult strict = service.trace(model,
                new LineageQuery(a.id(), LineageQuery.Direction.DOWNSTREAM, 8, false, 0.40));
        assertTrue(strict.nodes().stream().noneMatch(n -> n.id().equals(weak.id())),
                "below-threshold confidence must be excluded");
        assertTrue(strict.nodes().stream().noneMatch(n -> n.id().equals(guessed.id())),
                "inferred edges must be excludable");
        assertTrue(strict.nodes().stream().anyMatch(n -> n.id().equals(strong.id())));

        LineageResult lenient = service.trace(model,
                new LineageQuery(a.id(), LineageQuery.Direction.DOWNSTREAM, 8, true, 0.40));
        assertTrue(lenient.nodes().stream().anyMatch(n -> n.id().equals(guessed.id())),
                "inferred edges appear when included");
    }

    @Test
    void unresolvedEdgesBecomeGapsNeverPathSegments() {
        SoftwareModel model = new SoftwareModel();
        Entity a = method("A#a()");
        model.addEntity(a);
        model.addRelationship(invokes(a.id(), "External#gone()", 0.40, ResolutionStatus.UNRESOLVED));

        LineageResult result = service.trace(model, LineageQuery.downstream(a.id()));
        assertTrue(result.paths().isEmpty(), "an unresolved edge must not extend a path");
        assertEquals(1, result.gaps().size());
        assertEquals(LineageResult.Gap.UNRESOLVED_TARGET, result.gaps().get(0).kind());
    }

    @Test
    void traversalOrderingIsDeterministic() {
        SoftwareModel model = new SoftwareModel();
        Entity a = method("A#a()");
        model.addEntity(a);
        // Insert branches in a scrambled order; results must not depend on it.
        for (String name : new String[]{"Z#z()", "M#m()", "B#b()"}) {
            Entity e = method(name);
            model.addEntity(e);
            model.addRelationship(invokes(a.id(), e.id(), 0.95, ResolutionStatus.RESOLVED));
        }
        LineageResult first = service.trace(model, LineageQuery.downstream(a.id()));
        LineageResult second = service.trace(model, LineageQuery.downstream(a.id()));
        assertEquals(first, second, "two traversals of the same model must be identical");
        assertEquals(3, first.paths().size());
        assertTrue(first.paths().get(0).nodeIds().get(1).compareTo(
                first.paths().get(2).nodeIds().get(1)) < 0, "paths must be sorted");
    }
}
