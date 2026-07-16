package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.tools.Views;

import java.util.Locale;

/**
 * Shared, side-effect-free helpers for the onboarding analyzers: language
 * detection from stable-id prefixes, evidence-reference construction, name
 * normalization (for the deliberately conservative cross-language name match) and
 * confidence bands. Kept in one place so every stage phrases things identically.
 */
final class OnboardingText {

    /** Upper bound when scanning "all entities of a kind"; also a documented limit. */
    static final int CANDIDATE_CAP = 1000;

    private OnboardingText() {
    }

    static boolean isJava(String stableId) {
        return stableId != null && stableId.startsWith("java:");
    }

    static boolean isAda(String stableId) {
        return stableId != null && stableId.startsWith("ada:");
    }

    static String languageOf(String stableId) {
        if (isJava(stableId)) {
            return "java";
        }
        if (isAda(stableId)) {
            return "ada";
        }
        return "other";
    }

    static EvidenceRef ref(Views.EntityView e) {
        return new EvidenceRef(e.stableId(), e.location());
    }

    /** Lowercases and drops underscores, so Java {@code calculateRoute} and Ada
     *  {@code Calculate_Route} compare equal. Used only for clearly-labelled
     *  inferred cross-language candidates, never to assert a fact. */
    static String normalizeName(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("_", "");
    }

    /** The trailing token of a stable id (after the last colon). */
    static String shortId(String stableId) {
        int c = stableId.lastIndexOf(':');
        return c >= 0 ? stableId.substring(c + 1) : stableId;
    }

    static String band(double confidence) {
        String label = confidence >= 0.85 ? "High" : confidence >= 0.60 ? "Medium" : "Low";
        return label + String.format(Locale.ROOT, " (%.2f)", confidence);
    }
}
