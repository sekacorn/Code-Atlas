package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.SourceLocation;

/**
 * A behaviour whose cyclomatic complexity exceeds the configured threshold, with a
 * qualitative risk band. Always carries a source location so it is actionable.
 */
public record ComplexityHotspot(String stableId,
                                String qualifiedName,
                                int complexity,
                                Risk risk,
                                SourceLocation location) {

    public enum Risk {
        MODERATE, HIGH, VERY_HIGH
    }

    static ComplexityHotspot from(Entity e, int complexity) {
        Risk risk = complexity >= 30 ? Risk.VERY_HIGH : complexity >= 15 ? Risk.HIGH : Risk.MODERATE;
        return new ComplexityHotspot(e.id(), e.qualifiedName(), complexity, risk,
                e.location().orElse(SourceLocation.ofFile("unknown")));
    }
}
