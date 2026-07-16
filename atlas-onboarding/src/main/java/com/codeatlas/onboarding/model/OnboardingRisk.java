package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 9 output: one onboarding risk, categorized so a reader can tell an analysis
 * limitation apart from an actual repository problem. Code Atlas never presents its
 * own blind spots as software defects.
 *
 * @param category    how to read the risk
 * @param title       a short label
 * @param description what it is and why it matters for onboarding
 * @param evidence    supporting references (may be empty for standing blind spots)
 */
public record OnboardingRisk(RiskCategory category,
                             String title,
                             String description,
                             List<EvidenceRef> evidence) {
}
