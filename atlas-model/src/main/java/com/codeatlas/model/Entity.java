package com.codeatlas.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single discovered artifact in the software model.
 *
 * <p>Every entity carries a deterministic, <em>location-independent</em> stable
 * {@link #id()} (see {@link #stableId(String, EntityKind, String)}) so relationships
 * can reference it across parsers, across incremental runs, and after lines move.
 * The {@link #location()} is retained as evidence but does not define identity.
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

        // Ada spec/body merge evidence.
        public static final String ADA_PART = "adaPart";          // spec | body
        public static final String HAS_SPEC = "hasSpec";          // true when a specification was seen
        public static final String HAS_BODY = "hasBody";          // true when a body was seen
        public static final String SPEC_LOCATION = "specLocation"; // "file:line" of the specification
        public static final String BODY_LOCATION = "bodyLocation"; // "file:line" of the body

        // Java lineage extraction (populated by the Java parser, consumed by the
        // lineage analyzer; DTOs stay ordinary type entities with a role, never
        // duplicate entities).
        public static final String ROLE = "role";                          // controller|service|repository|mapper-interface|dto-request|dto-response
        public static final String HTTP_METHOD = "httpMethod";             // GET|POST|...
        public static final String HTTP_PATH = "httpPath";                 // normalized /a/{b}
        public static final String HTTP_PATH_UNRESOLVED = "httpPathUnresolved"; // true when not statically resolvable
        public static final String HTTP_PARAMS = "httpParams";             // "id:path,name:query,..." evidence
        public static final String REQUEST_BODY_TYPE = "requestBodyType";  // simple source-spelled type
        public static final String RETURN_TYPE_NORMALIZED = "returnTypeNormalized"; // wrappers unwrapped
        public static final String PARAM_TYPES = "paramTypes";             // comma-joined source-spelled types
        public static final String VALIDATED = "validated";                // true when @Valid/@Validated present
        public static final String JPA_ENTITY = "jpaEntity";               // true on @Entity types
        public static final String JPA_TABLE_NAME = "jpaTableName";        // explicit @Table(name=...)
        public static final String SPRING_DATA_REPOSITORY = "springDataRepository"; // true on Spring Data interfaces
        public static final String MANAGED_ENTITY_TYPE = "managedEntityType"; // first type arg of JpaRepository<...>
        public static final String DB_OBJECT_TYPE = "dbObjectType";        // table (views/procs are future work)
        public static final String TRANSFORMATION = "transformation";      // true on detected mapping methods

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
     * Builds a deterministic, location-independent stable identifier from the
     * entity's language, kind and qualified name. The grammar is
     * {@code <lang>:<kind-token>:<qualifiedName>}, with two special cases:
     * files are {@code file:<relativePath>} and the project root is
     * {@code project:<name>}. See {@code STABLE_IDENTIFIERS.md} for the full
     * grammar and normalization rules.
     *
     * <p>Because it never includes a line, column, timestamp, database key or
     * absolute path, the same declaration yields the same id across scans and after
     * unrelated edits move it within its file.
     */
    public static String stableId(String language, EntityKind kind, String qualifiedName) {
        if (kind == EntityKind.FILE) {
            return "file:" + qualifiedName;
        }
        if (kind == EntityKind.PROJECT) {
            return "project:" + qualifiedName;
        }
        // Configuration files are identified by path, like files (e.g.
        // config:application.yml), not by an extra language token.
        if (kind == EntityKind.CONFIGURATION) {
            return "config:" + qualifiedName;
        }
        String lang = (language == null || language.isBlank()
                || language.equals("unknown") || language.equals("n/a")) ? "code" : language;
        return lang + ":" + kindToken(kind) + ":" + qualifiedName;
    }

    private static String kindToken(EntityKind kind) {
        return switch (kind) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, TYPE, PROTECTED_TYPE, GENERIC -> "type";
            case METHOD -> "method";
            case CONSTRUCTOR -> "constructor";
            case FUNCTION -> "function";
            case PROCEDURE -> "procedure";
            case FIELD -> "field";
            case VARIABLE -> "variable";
            case PACKAGE -> "package";
            case NAMESPACE -> "namespace";
            case TASK -> "task";
            case EXCEPTION -> "exception";
            case MODULE -> "module";
            case CONFIGURATION -> "config";
            // Only tables are modeled today; views/procedures will refine this token.
            case DATABASE_OBJECT -> "table";
            case DATA_SOURCE -> "source";
            case DATA_SINK -> "sink";
            case ENDPOINT -> "endpoint";
            case WORKFLOW -> "workflow";
            case DEPENDENCY -> "dependency";
            case FILE -> "file";
            case PROJECT -> "project";
        };
    }

    public String id() {
        return id;
    }

    /**
     * The authoritative, stable, location-independent identity for external
     * references (reports, saved searches, suppressions, agent tool calls).
     * Identical to {@link #id()}: the stable id <em>is</em> the entity's identity.
     */
    public String stableId() {
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

    /**
     * Merges two entities that share a stable id into one, combining their
     * attributes and evidence. The caller (the model) decides whether a merge is
     * legitimate; this method only performs it.
     *
     * <p>Rules: exposure is OR-ed (visible anywhere → visible); complexity and LOC
     * take the maximum; SPARK contracts and other descriptive attributes are kept
     * from whichever declaration supplied them; and Ada {@code spec}/{@code body}
     * parts are recorded as spec/body evidence (locations + has-spec/has-body). The
     * specification is preferred as the canonical {@link #location()} when present.
     */
    public static Entity merge(Entity existing, Entity incoming) {
        Builder b = existing.toBuilder();

        for (Map.Entry<String, String> e : incoming.attributes().entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            switch (key) {
                case Attributes.EXTERNALLY_EXPOSED -> b.attribute(key,
                        existing.boolAttribute(key, false) || Boolean.parseBoolean(value));
                case Attributes.CYCLOMATIC_COMPLEXITY, Attributes.LINES_OF_CODE -> b.attribute(key,
                        Math.max(existing.intAttribute(key, 0), parseIntOr(value, 0)));
                default -> {
                    if (existing.attribute(key).isEmpty()) {
                        b.attribute(key, value);
                    }
                }
            }
        }

        recordPartEvidence(b, existing);
        recordPartEvidence(b, incoming);

        // Prefer the specification as canonical evidence when the incoming part is a spec.
        if ("spec".equals(incoming.attributes().get(Attributes.ADA_PART))) {
            incoming.location().ifPresent(b::location);
        }
        return b.build();
    }

    /** Folds one declaration's Ada spec/body part into the merged evidence. */
    private static void recordPartEvidence(Builder b, Entity part) {
        String p = part.attributes().get(Attributes.ADA_PART);
        if (p == null) {
            return;
        }
        String loc = part.location().map(SourceLocation::toString).orElse(null);
        if (p.equals("spec")) {
            b.attribute(Attributes.HAS_SPEC, true);
            if (loc != null) {
                b.attribute(Attributes.SPEC_LOCATION, loc);
            }
        } else if (p.equals("body")) {
            b.attribute(Attributes.HAS_BODY, true);
            if (loc != null) {
                b.attribute(Attributes.BODY_LOCATION, loc);
            }
        }
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
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
                id = stableId(language, kind, qualifiedName != null ? qualifiedName : name);
            }
            return new Entity(this);
        }
    }
}
