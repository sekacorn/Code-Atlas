package com.codeatlas.model;

/**
 * A problem detected while building the model that the user should see &mdash; most
 * importantly, a <em>stable-id collision</em>: two distinct declarations that
 * resolved to the same identifier. Collisions are never silently overwritten; they
 * are recorded here with the evidence of both declarations so nothing is lost.
 *
 * @param code    short machine-readable code, e.g. {@code STABLE_ID_COLLISION}
 * @param message human-readable explanation, including the affected locations
 */
public record Diagnostic(String code, String message) {

    public static final String STABLE_ID_COLLISION = "STABLE_ID_COLLISION";
}
