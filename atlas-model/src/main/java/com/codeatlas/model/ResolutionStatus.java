package com.codeatlas.model;

/**
 * How confidently a relationship's target is known. This makes the model's
 * uncertainty explicit rather than hiding it behind a single boolean, so reports
 * and (future) agents never present a guess as a confirmed fact.
 *
 * <p>The four states follow the enhancement addendum:
 * <ul>
 *   <li>{@link #DISCOVERED} — explicitly present in source/config/build (a fact).</li>
 *   <li>{@link #RESOLVED} — connected through deterministic symbol resolution.</li>
 *   <li>{@link #INFERRED} — suggested by naming/convention/architecture rules.</li>
 *   <li>{@link #UNRESOLVED} — a reference was detected but its target is unknown.</li>
 * </ul>
 */
public enum ResolutionStatus {
    DISCOVERED,
    RESOLVED,
    INFERRED,
    UNRESOLVED
}
