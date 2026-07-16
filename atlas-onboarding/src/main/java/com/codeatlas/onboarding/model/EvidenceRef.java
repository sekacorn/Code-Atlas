package com.codeatlas.onboarding.model;

/**
 * A single evidence pointer: a stable id and, where available, the {@code file:line}
 * location that backs a statement. Every onboarding finding cites at least one.
 */
public record EvidenceRef(String stableId, String location) {

    public static EvidenceRef of(String stableId) {
        return new EvidenceRef(stableId, "");
    }

    public String render() {
        return location == null || location.isBlank() ? stableId : stableId + " (" + location + ")";
    }
}
