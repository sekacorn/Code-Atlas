package com.codeatlas.onboarding.model;

/**
 * How complete a stage's (or the whole workflow's) result is. A stage that throws
 * is recorded {@link #FAILED} and the remaining stages still run — a single stage
 * failure never aborts the workflow.
 */
public enum Completeness {
    /** Ran to completion with all expected evidence available. */
    COMPLETE,
    /** Ran, but some inputs were missing or incomplete (honest partial result). */
    PARTIAL,
    /** Could not run because a prerequisite was absent (e.g. no completed scan). */
    UNAVAILABLE,
    /** Threw an error; the error is recorded and later stages continue. */
    FAILED
}
