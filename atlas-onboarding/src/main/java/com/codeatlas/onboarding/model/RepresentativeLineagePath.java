package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 7 output: one representative data-lineage path chosen to teach a new
 * developer how data moves through the system. A small, deterministic sample —
 * never a claim to represent every path.
 *
 * @param title             a readable title ("POST /missions → … → Mission_State")
 * @param startId           stable id of the path's start
 * @param endId             stable id of the path's end
 * @param orderedNodes      node stable ids in flow order (start → end)
 * @param orderedEdges      edges rendered as "from -[KIND]-> to" in flow order
 * @param relationshipTypes distinct relationship kinds on the path, sorted
 * @param confidence        a confidence band (weakest edge on the path)
 * @param evidence          per-edge evidence references
 * @param unresolvedGaps    unresolved segments discovered on the path
 * @param blindSpots        the standing static-analysis blind spots that apply
 * @param partial           true when the path has unresolved gaps or was truncated
 */
public record RepresentativeLineagePath(String title,
                                        String startId,
                                        String endId,
                                        List<String> orderedNodes,
                                        List<String> orderedEdges,
                                        List<String> relationshipTypes,
                                        String confidence,
                                        List<EvidenceRef> evidence,
                                        List<String> unresolvedGaps,
                                        List<String> blindSpots,
                                        boolean partial) {
    public RepresentativeLineagePath {
        orderedNodes = List.copyOf(orderedNodes);
        orderedEdges = List.copyOf(orderedEdges);
        relationshipTypes = List.copyOf(relationshipTypes);
        evidence = List.copyOf(evidence);
        unresolvedGaps = List.copyOf(unresolvedGaps);
        blindSpots = List.copyOf(blindSpots);
    }
}
