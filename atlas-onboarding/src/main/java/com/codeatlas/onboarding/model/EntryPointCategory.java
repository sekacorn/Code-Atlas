package com.codeatlas.onboarding.model;

/**
 * What kind of entry point something is, independent of how it is displayed.
 *
 * <p>Stages downstream (lineage sampling, boundary discovery) need to ask "is this a
 * main?" without depending on the human-readable {@code type} label — a label may be
 * reworded at any time, and matching on it silently breaks callers.
 */
public enum EntryPointCategory {
    /** An executable main unit: a JVM {@code main}, an Ada main, or one a build declares. */
    MAIN,
    /** A network-facing request handler (e.g. a REST endpoint). */
    ENDPOINT,
    /** A concurrent unit that runs on its own (e.g. an Ada task). */
    TASK
}
