package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.SoftwareModel;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Flags behaviours whose cyclomatic complexity exceeds a threshold, ordered most
 * complex first. Complexity itself is supplied by the parsers (a deterministic
 * count of decision points), so this analyzer only ranks and bands it.
 */
public final class ComplexityAnalyzer {

    private static final Set<EntityKind> BEHAVIOURS =
            EnumSet.of(EntityKind.METHOD, EntityKind.CONSTRUCTOR, EntityKind.FUNCTION, EntityKind.PROCEDURE);

    private final int threshold;

    public ComplexityAnalyzer(int threshold) {
        this.threshold = threshold;
    }

    /** Default threshold of 10, the conventional "start reviewing" line. */
    public ComplexityAnalyzer() {
        this(10);
    }

    public List<ComplexityHotspot> analyze(SoftwareModel model) {
        return model.entities().stream()
                .filter(e -> BEHAVIOURS.contains(e.kind()))
                .map(e -> new Object() {
                    final Entity entity = e;
                    final int complexity = e.intAttribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, 1);
                })
                .filter(x -> x.complexity >= threshold)
                .map(x -> ComplexityHotspot.from(x.entity, x.complexity))
                .sorted(Comparator.comparingInt(ComplexityHotspot::complexity).reversed())
                .toList();
    }
}
