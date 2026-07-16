package com.codeatlas.onboarding.model;

/**
 * The kind of Java↔Ada (or Java↔non-Java) boundary, graded by the strength of the
 * evidence behind it. A direct source-level call between Java and Ada usually does
 * not exist, so most real boundaries are indirect (shared data, a process, a
 * message, a native shim) — this vocabulary keeps that distinction explicit and
 * never overstates a boundary as direct when it is inferred.
 */
public enum BoundaryType {
    /** A native (JNI) method or an otherwise direct call into non-Java code. */
    DIRECT_BOUNDARY,
    /** Both sides read/write the same data store (shared identity, not shared name). */
    SHARED_DATA_BOUNDARY,
    /** One side sends/receives via a message/queue API. */
    MESSAGE_BOUNDARY,
    /** One side launches the other as an external process. */
    PROCESS_BOUNDARY,
    /** Communication over sockets/HTTP between the two sides. */
    NETWORK_BOUNDARY,
    /** Both sides are wired through a shared configuration key. */
    CONFIGURATION_BOUNDARY,
    /** A real cross-language reference exists but the counterpart is name-matched only. */
    INFERRED_BOUNDARY,
    /** Evidence of a boundary exists but its far side is outside the analyzed sources. */
    UNRESOLVED_BOUNDARY
}
