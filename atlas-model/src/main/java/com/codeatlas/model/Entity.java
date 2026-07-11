package com.codeatlas.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single discovered artifact in the software model.
 *
 * <p>Every entity carries a stable {@link #id()} so relationships can reference it
 * across parsers and across incremental runs. Ids are deterministic (see
 * {@link #idFor}) so re-scanning an unchanged file yields the same graph.
 *
 * <p>Free-form {@link #attributes()} hold parser- and analysis-supplied facts
 * (lines of code, cyclomatic complexity, visibility, "externally exposed" flags,
 * confidence scores) without bloating the core type. Keys are documented in
 * {@link Attributes}.
 */
public final class Entity {

    /** Well-known attribute keys, so producers and consumers agree on spelling. */
    public static final class Attributes {
        public static final String LINES_OF_CODE = "loc";
        public static final String COMMENT_LINES = "commentLines";
        public static final String BLANK_LINES = "blankLines";
        public static final String CYCLOMATIC_COMPLEXITY = "cyclomaticComplexity";
        public static final String VISIBILITY = "visibility";          // public|private|protected|package
        public static final String EXTERNALLY_EXPOSED = "externallyExposed"; // true|false
        public static final String SIGNATURE = "signature";
        public static final String RETURN_TYPE = "returnType";
        public static final String DOC = "doc";
        public static final String FILE_HASH = "fileHash";
        public static final String LANGUAGE_FEATURE = "languageFeature"; // e.g. spark-contract, generic

        private Attributes() {
        }
    }

    private final String id;
    private final EntityKind kind;
    private final String name;
    private final String qualifiedName;
    private final String language;
    private final SourceLocation location;
    private final Map<String, String> attributes;

    private Entity(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.name = Objects.requireNonNull(b.name, "name");
        this.qualifiedName = b.qualifiedName != null ? b.qualifiedName : b.name;
        this.language = b.language != null ? b.language : "unknown";
        this.location = b.location;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(b.attributes));
    }

    /**
     * Builds a deterministic id from kind + qualified name, disambiguated by
     * location when present. Two runs over identical source produce identical ids.
     */
    public static String idFor(EntityKind kind, String qualifiedName, SourceLocation location) {
        StringBuilder sb = new StringBuilder(kind.name()).append(':').append(qualifiedName);
        if (location != null && location.startLine() > 0) {
            sb.append('@').append(location.filePath()).append('#').append(location.startLine());
        } else if (location != null) {
            sb.append('@').append(location.filePath());
        }
        return sb.toString();
    }

    public String id() {
        return id;
    }

    public EntityKind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    public String language() {
        return language;
    }

    public Optional<SourceLocation> location() {
        return Optional.ofNullable(location);
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    public int intAttribute(String key, int defaultValue) {
        String v = attributes.get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean boolAttribute(String key, boolean defaultValue) {
        String v = attributes.get(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public static Builder builder(EntityKind kind, String name) {
        return new Builder(kind, name);
    }

    /** Returns a builder pre-populated from this entity, for cheap derived copies. */
    public Builder toBuilder() {
        Builder b = new Builder(kind, name)
                .id(id)
                .qualifiedName(qualifiedName)
                .language(language)
                .location(location);
        b.attributes.putAll(attributes);
        return b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Entity other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return kind + " " + qualifiedName + (location != null ? " (" + location + ")" : "");
    }

    public static final class Builder {
        private String id;
        private final EntityKind kind;
        private final String name;
        private String qualifiedName;
        private String language;
        private SourceLocation location;
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(EntityKind kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder qualifiedName(String qualifiedName) {
            this.qualifiedName = qualifiedName;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
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

        public Builder attribute(String key, int value) {
            this.attributes.put(key, Integer.toString(value));
            return this;
        }

        public Builder attribute(String key, boolean value) {
            this.attributes.put(key, Boolean.toString(value));
            return this;
        }

        public Entity build() {
            if (id == null) {
                id = idFor(kind, qualifiedName != null ? qualifiedName : name, location);
            }
            return new Entity(this);
        }
    }
}
