package com.codeatlas.model;

/**
 * The kinds of directed relationship that can hold between two {@link Entity} instances.
 *
 * <p>Every relationship in the model answers part of the platform's core question:
 * where does data come from, what transforms it, where does it go, and who consumes it.
 */
public enum RelationshipKind {
    /** Structural nesting: a package contains a class, a class contains a method. */
    CONTAINS,
    /** A behaviour invokes another behaviour. */
    CALLS,
    /** A symbol reads or names another symbol without invoking it. */
    REFERENCES,
    /** A type extends another type. */
    INHERITS,
    /** A type implements an interface / realises a contract. */
    IMPLEMENTS,
    /** A file imports / withs another unit. */
    IMPORTS,
    /** A module or unit depends on another. */
    DEPENDS_ON,
    /**
     * A build project declares a unit as an executable entry point (e.g. a GNAT
     * project's {@code for Main use (...)}). This is a <em>declared</em> entry
     * point — stronger evidence than any naming or shape heuristic.
     */
    DECLARES_MAIN,
    /** Configuration wires up a code entity. */
    CONFIGURES,
    /** General use relationship when nothing more specific applies. */
    USES,
    /** A code entity maps to a data/schema entity. */
    MAPS_TO,
    /** A code entity persists to a database object. */
    PERSISTS_TO,
    /** A unit generates another artifact. */
    GENERATES,
    /** A generic unit is instantiated. */
    INSTANTIATES,
    /** An Ada renaming declaration. */
    RENAMES,
    /** A behaviour may raise/throw an exception. */
    THROWS,

    // ---- data-lineage kinds (each is produced by a documented ATLAS-LINEAGE rule) ----

    /** A type exposes an HTTP endpoint (controller class → endpoint). */
    EXPOSES,
    /** A request or caller invokes a handler/target method (endpoint → handler, method → method). */
    INVOKES,
    /** A step takes a data type as input (endpoint/transformation → input DTO). */
    CONSUMES,
    /** A step yields a data type as output (transformation/endpoint → output DTO/entity). */
    PRODUCES,
    /** A behaviour reads from a data store (method → table). */
    READS_FROM,
    /** A behaviour writes to a data store (method → table). */
    WRITES_TO,
    /** A data type is validated by a validation step (DTO → validator method). */
    VALIDATED_BY,
    /** A repository/DAO manages a persistent entity type (repository → JPA entity). */
    MANAGES
}
