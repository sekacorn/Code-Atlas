package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 8 output: one of the components a new developer should study first, chosen
 * by a deterministic multi-signal ranking (never one metric alone) and described
 * with a deterministic summary. Confirmed structural facts are separated from the
 * inferred responsibility statement.
 *
 * @param stableId     the component's stable id
 * @param displayName  qualified name
 * @param language     "java" or "ada"
 * @param purpose      inferred responsibility (explicitly an inference)
 * @param inputs       inputs (parameters/consumed types) — confirmed
 * @param outputs      outputs (returns/produced types) — confirmed
 * @param callers      who calls in
 * @param callees      who it calls
 * @param dependencies what it depends on
 * @param dependents   what depends on it
 * @param dataSources  data sources it reads
 * @param dataSinks    data stores/sinks it writes
 * @param sideEffects  detected write side effects
 * @param complexity   highest member cyclomatic complexity (or its own)
 * @param score        the ranking score (higher = more central)
 * @param scoreBasis   a human-readable breakdown of the signals that scored it
 * @param evidence     supporting references
 * @param limitations  what the summary cannot see
 */
public record CentralComponentSummary(String stableId,
                                      String displayName,
                                      String language,
                                      String purpose,
                                      List<String> inputs,
                                      List<String> outputs,
                                      List<String> callers,
                                      List<String> callees,
                                      List<String> dependencies,
                                      List<String> dependents,
                                      List<String> dataSources,
                                      List<String> dataSinks,
                                      List<String> sideEffects,
                                      int complexity,
                                      int score,
                                      String scoreBasis,
                                      List<EvidenceRef> evidence,
                                      List<String> limitations) {
    public CentralComponentSummary {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        callers = List.copyOf(callers);
        callees = List.copyOf(callees);
        dependencies = List.copyOf(dependencies);
        dependents = List.copyOf(dependents);
        dataSources = List.copyOf(dataSources);
        dataSinks = List.copyOf(dataSinks);
        sideEffects = List.copyOf(sideEffects);
        evidence = List.copyOf(evidence);
        limitations = List.copyOf(limitations);
    }
}
