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
    THROWS
}
