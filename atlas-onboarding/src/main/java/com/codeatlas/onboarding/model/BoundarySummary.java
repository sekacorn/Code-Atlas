package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 6 output: one discovered boundary between the Java side and the Ada (or
 * other non-Java) side. A boundary is only emitted when a real crossing signal
 * exists — a native method, a process/message API reference, a shared data store,
 * or an actual unresolved cross-language reference. Two entities that merely share
 * a similar name never produce a boundary.
 *
 * @param type               the graded boundary type
 * @param javaSideId         stable id of the Java-side participant
 * @param javaSideLabel      readable Java-side label
 * @param adaSideId          stable id of the Ada-side participant, or "" when the
 *                           counterpart is outside the analyzed sources
 * @param adaSideLabel       readable Ada-side label, or an "unresolved" note
 * @param sharedArtifact     what is shared/crossed (a table, a native symbol, a
 *                           process command, a message channel)
 * @param evidence           supporting references (both sides where available)
 * @param confidence         a confidence band with its basis
 * @param missingInformation what the developer must confirm (never hidden)
 * @param resolutionStatus   DISCOVERED / INFERRED / UNRESOLVED
 */
public record BoundarySummary(BoundaryType type,
                              String javaSideId,
                              String javaSideLabel,
                              String adaSideId,
                              String adaSideLabel,
                              String sharedArtifact,
                              List<EvidenceRef> evidence,
                              String confidence,
                              String missingInformation,
                              String resolutionStatus) {
}
