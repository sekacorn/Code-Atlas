package com.codeatlas.onboarding;

import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.OnboardingRisk;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.onboarding.model.RiskCategory;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 9: onboarding risks, categorized so a reader can tell an analysis
 * limitation from a real repository problem. Standing static-analysis blind spots
 * are always listed as limitations, never as defects.
 */
final class RiskReviewer {

    private static final List<String> STANDING_BLIND_SPOTS = List.of(
            "Reflection and dynamic dispatch are invisible to static analysis",
            "Dynamic SQL and runtime-built queries are not modeled",
            "Dependency-injection wiring and configuration-driven behavior may add paths not shown",
            "Native / JNI integration is a boundary whose far side is outside the analyzed sources",
            "Build-system membership is not parsed yet (no Maven/Gradle/.gpr parser)");

    private final AtlasToolApi api;

    RiskReviewer(AtlasToolApi api) {
        this.api = api;
    }

    List<OnboardingRisk> review(ScanHealthSummary health,
                                List<CentralComponentSummary> central,
                                List<RepresentativeLineagePath> paths) {
        List<OnboardingRisk> out = new ArrayList<>();

        // Repository problems: real defects in the repository / its parseability.
        if (health.exactFileCounts() && health.filesFailed() > 0) {
            out.add(new OnboardingRisk(RiskCategory.REPOSITORY_PROBLEM, "Parser failures",
                    health.filesFailed() + " file(s) failed to parse; their contents are not in the model.",
                    List.of()));
        }
        int diagnostics = api.getDiagnostics().value().size();
        if (diagnostics > 0) {
            out.add(new OnboardingRisk(RiskCategory.REPOSITORY_PROBLEM, "Scan diagnostics",
                    diagnostics + " diagnostic(s) recorded (e.g. duplicate qualified names / stable-id "
                            + "collisions) - these can distort resolution.", List.of()));
        }

        // External dependencies: qualified references (Pkg.Member or Type#member) that
        // could not be resolved. Restricting to qualified targets avoids flagging simple
        // unresolved names - language builtins, generic type args and intra-repo
        // resolution gaps - as external systems.
        TreeSet<String> externals = new TreeSet<>();
        for (Views.UnresolvedReference u : api.getUnresolvedReferences(CANDIDATE_CAP).value()) {
            String name = u.targetName();
            int cut = name.indexOf('#') >= 0 ? name.indexOf('#') : name.indexOf('.');
            if (cut > 0) {
                externals.add(name.substring(0, cut));
            }
        }
        externals.stream().limit(8).forEach(name ->
                out.add(new OnboardingRisk(RiskCategory.EXTERNAL_DEPENDENCY, "External reference: " + name,
                        "'" + name + "' is referenced but not part of the analyzed repository - an external "
                                + "system or library to confirm.", List.of())));

        // Potential architectural risks: high complexity, weak-evidence central
        // components, and lineage paths with unresolved segments.
        for (ComplexityHotspot h : api.getComplexity(5).value()) {
            out.add(new OnboardingRisk(RiskCategory.POTENTIAL_ARCHITECTURAL_RISK,
                    "High complexity: " + h.qualifiedName(),
                    "Cyclomatic complexity " + h.complexity() + " - a hotspot worth careful review.",
                    List.of(new EvidenceRef(h.qualifiedName(), String.valueOf(h.location())))));
        }
        central.stream().filter(c -> c.scoreBasis().contains("unresolved")).forEach(c ->
                out.add(new OnboardingRisk(RiskCategory.POTENTIAL_ARCHITECTURAL_RISK,
                        "Central component with unresolved dependencies: " + c.displayName(),
                        "A high-centrality component whose evidence includes unresolved references.",
                        List.of(EvidenceRef.of(c.stableId())))));
        paths.stream().filter(RepresentativeLineagePath::partial).forEach(p ->
                out.add(new OnboardingRisk(RiskCategory.POTENTIAL_ARCHITECTURAL_RISK,
                        "Lineage path with unresolved segment(s)",
                        "The representative path '" + p.title() + "' has unresolved or truncated segments.",
                        List.of(EvidenceRef.of(p.startId())))));

        // Standing analysis limitations (never presented as defects).
        for (String blindSpot : STANDING_BLIND_SPOTS) {
            out.add(new OnboardingRisk(RiskCategory.ANALYSIS_LIMITATION, "Analysis limitation",
                    blindSpot, List.of()));
        }
        return List.copyOf(out);
    }
}
