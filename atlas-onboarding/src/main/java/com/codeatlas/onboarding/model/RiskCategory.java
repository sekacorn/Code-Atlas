package com.codeatlas.onboarding.model;

/**
 * How an onboarding risk should be read. The distinction matters: an unresolved
 * reference is usually an <em>analysis limitation</em>, not a defect in the
 * repository. Code Atlas never presents its own blind spots as software bugs.
 */
public enum RiskCategory {
    /** A gap in what static analysis can see (reflection, dynamic SQL, no build parser…). */
    ANALYSIS_LIMITATION,
    /** A concrete problem in the repository (a file that failed to parse). */
    REPOSITORY_PROBLEM,
    /** A dependency on something outside the analyzed sources. */
    EXTERNAL_DEPENDENCY,
    /** A structural property worth a human's judgement (high complexity, weak evidence). */
    POTENTIAL_ARCHITECTURAL_RISK
}
