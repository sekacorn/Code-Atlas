package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * The record every workflow stage produces: its name, how complete it is, what it
 * consumed, a one-line output summary, the evidence it leaned on, any warnings, and
 * how long it took. Durations are volatile and are deliberately excluded from the
 * deterministic JSON body (they appear only in text/HTML/performance output).
 *
 * @param name         stage name (e.g. "Entry-Point Discovery")
 * @param completeness COMPLETE / PARTIAL / UNAVAILABLE / FAILED
 * @param inputs       what the stage read (scan id, prior-stage outputs)
 * @param output       a short human summary of what the stage produced
 * @param evidence     representative evidence references
 * @param warnings     honest warnings (missing inputs, capability gaps, errors)
 * @param durationMillis wall-clock duration — volatile, not part of deterministic content
 */
public record OnboardingStageResult(String name,
                                    Completeness completeness,
                                    List<String> inputs,
                                    String output,
                                    List<EvidenceRef> evidence,
                                    List<String> warnings,
                                    long durationMillis) {
}
