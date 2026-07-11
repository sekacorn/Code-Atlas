package com.codeatlas.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A directed edge between two entities, identified by their stable ids.
 *
 * <p>Relationships may reference a target that is not (yet) resolved to a concrete
 * entity in the model. In that case {@link #toId()} holds a best-effort symbolic
 * key (e.g. an unresolved method name) and {@link #resolved()} is {@code false}.
 * Analysis treats unresolved edges conservatively so it never overstates a finding.
 */
public final class Relationship {

    private final String fromId;
    private final String toId;
    private final RelationshipKind kind;
    private final boolean resolved;
    private final ResolutionStatus status;
    private final SourceLocation location;
    private final Map<String, String> attributes;

    private Relationship(Builder b) {
        this.fromId = Objects.requireNonNull(b.fromId, "fromId");
        this.toId = Objects.requireNonNull(b.toId, "toId");
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.resolved = b.resolved;
        this.status = b.status != null ? b.status : deriveStatus(b.kind, b.resolved);
        this.location = b.location;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
    }

    /**
     * Default status when none is set explicitly: structural containment is a
     * DISCOVERED fact; any other resolved edge is RESOLVED; an unresolved edge is
     * UNRESOLVED. Callers may override via {@link Builder#status}.
     */
    private static ResolutionStatus deriveStatus(RelationshipKind kind, boolean resolved) {
        if (!resolved) {
            return ResolutionStatus.UNRESOLVED;
        }
        return kind == RelationshipKind.CONTAINS ? ResolutionStatus.DISCOVERED : ResolutionStatus.RESOLVED;
    }

    public String fromId() {
        return fromId;
    }

    public String toId() {
        return toId;
    }

    public RelationshipKind kind() {
        return kind;
    }

    /** Whether {@link #toId()} points at a concrete entity known to the model. */
    public boolean resolved() {
        return resolved;
    }

    /** The explicit or derived resolution status of this edge's target. */
    public ResolutionStatus status() {
        return status;
    }

    public Optional<SourceLocation> location() {
        return Optional.ofNullable(location);
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public static Builder builder(RelationshipKind kind, String fromId, String toId) {
        return new Builder(kind, fromId, toId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Relationship r
                && kind == r.kind
                && fromId.equals(r.fromId)
                && toId.equals(r.toId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, fromId, toId);
    }

    @Override
    public String toString() {
        return fromId + " -" + kind + "-> " + toId + (resolved ? "" : " (unresolved)");
    }

    public static final class Builder {
        private final RelationshipKind kind;
        private final String fromId;
        private final String toId;
        private boolean resolved = true;
        private ResolutionStatus status;
        private SourceLocation location;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(RelationshipKind kind, String fromId, String toId) {
            this.kind = kind;
            this.fromId = fromId;
            this.toId = toId;
        }

        public Builder resolved(boolean resolved) {
            this.resolved = resolved;
            return this;
        }

        /** Sets an explicit resolution status (otherwise it is derived). */
        public Builder status(ResolutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder location(SourceLocation location) {
            this.location = location;
            return this;
        }

        public Builder attribute(String key, String value) {
            if (value != null) {
                this.attributes.put(key, value);
            }
            return this;
        }

        public Relationship build() {
            return new Relationship(this);
        }
    }
}
