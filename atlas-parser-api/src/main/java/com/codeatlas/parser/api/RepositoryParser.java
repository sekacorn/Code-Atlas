package com.codeatlas.parser.api;

/**
 * The extension point of the whole platform. Every language, configuration format
 * and proprietary file type is supported by an implementation of this interface.
 *
 * <p>Implementations are discovered at runtime via {@link java.util.ServiceLoader},
 * so a new parser is added simply by dropping a jar on the classpath that declares
 * a {@code META-INF/services/com.codeatlas.parser.api.RepositoryParser} entry &mdash;
 * no core code changes, no rebuild of the platform.
 *
 * <p>Contract:
 * <ul>
 *   <li>Implementations must be stateless and thread-safe; the pipeline calls
 *       {@link #parse} concurrently across files.</li>
 *   <li>{@link #parse} must never throw for malformed input &mdash; it records
 *       {@link ParseIssue}s and returns whatever it could extract.</li>
 *   <li>Every produced {@link com.codeatlas.model.Entity} must carry a source
 *       location so findings stay traceable.</li>
 * </ul>
 */
public interface RepositoryParser {

    /** Stable identifier, e.g. {@code "java"}, {@code "ada"}, {@code "sql"}. */
    String languageId();

    /** Human-readable name for reports, e.g. {@code "Java (JavaParser)"}. */
    String displayName();

    /**
     * Whether this parser can handle the given file. Typically an extension check,
     * but may inspect content for ambiguous formats.
     */
    boolean supports(ParseRequest request);

    /**
     * Parses one file into entities and relationships. Must be side-effect free and
     * must not throw for malformed input.
     */
    ParseResult parse(ParseRequest request);
}
