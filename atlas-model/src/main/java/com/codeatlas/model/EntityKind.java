package com.codeatlas.model;

/**
 * The kinds of software artifact the platform can model.
 *
 * <p>This enum is intentionally language-neutral. Java classes, Ada packages,
 * SQL tables and proprietary workflow files all map onto the same vocabulary so
 * that analysis, graph and reporting code never needs to special-case a language.
 */
public enum EntityKind {
    // Structural containers
    PROJECT,
    MODULE,
    FILE,
    PACKAGE,
    NAMESPACE,

    // Type-like declarations
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    TYPE,
    PROTECTED_TYPE,
    GENERIC,

    // Behaviour-like declarations
    METHOD,
    CONSTRUCTOR,
    FUNCTION,
    PROCEDURE,
    TASK,

    // Data-like declarations
    FIELD,
    VARIABLE,

    // Cross-cutting language features
    ANNOTATION,
    EXCEPTION,

    // Non-code artifacts
    CONFIGURATION,
    DATABASE_OBJECT,
    WORKFLOW,
    ENDPOINT,
    DEPENDENCY
}
