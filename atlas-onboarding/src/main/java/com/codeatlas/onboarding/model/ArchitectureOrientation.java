package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 5 output: a structural orientation to the system. Everything here except
 * confirmed structural counts is labelled inferred — architectural layers and
 * subsystem groupings are inferences from package names, roles and connectivity,
 * not declarations.
 *
 * @param majorModules   largest packages/modules by member count, with counts
 * @param inferredLayers likely architectural layers present (inferred from roles)
 * @param mostConnected  most-connected components by resolved edges
 * @param dataAccess     detected data-access components
 * @param externalFacing detected external-interface components (endpoints, I/O)
 * @param notes          additional inferred observations, each labelled
 */
public record ArchitectureOrientation(List<String> majorModules,
                                      List<String> inferredLayers,
                                      List<String> mostConnected,
                                      List<String> dataAccess,
                                      List<String> externalFacing,
                                      List<String> notes) {
}
