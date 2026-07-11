package com.codeatlas.core;

/**
 * Outcome of the linking pass over symbolic cross-reference edges.
 *
 * <p>These counts are the basis of honest coverage reporting: how many detected
 * references were connected to a concrete target, how many remained unresolved,
 * and how many were too ambiguous to resolve safely.
 *
 * @param resolved   references connected to at least one concrete target
 * @param unresolved references whose target could not be found
 * @param ambiguous  references with too many candidates to resolve confidently
 */
public record LinkStats(int resolved, int unresolved, int ambiguous) {

    public int totalReferences() {
        return resolved + unresolved + ambiguous;
    }

    /** Fraction of detected references that were resolved (0.0 when there are none). */
    public double resolutionRate() {
        int total = totalReferences();
        return total == 0 ? 1.0 : (double) resolved / total;
    }
}
