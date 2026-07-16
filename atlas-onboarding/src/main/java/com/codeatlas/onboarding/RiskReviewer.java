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
            "Build files are read literally: no property interpolation, no dependency management, "
                    + "no plugin or script evaluation, so some declared structure may be missed");

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

        // External dependencies are claimed only where "not in this repository" was
        // actually checked: a declared build coordinate that the Linker could not
        // match against any module here is genuinely third-party, and the coordinate
        // names it exactly.
        TreeSet<String> externals = new TreeSet<>();
        List<Views.UnresolvedReference> unresolved = api.getUnresolvedReferences(CANDIDATE_CAP).value();
        for (Views.UnresolvedReference u : unresolved) {
            if (u.kind().equals("DEPENDS_ON")) {
                externals.add(u.targetName()); // e.g. "org.slf4j:slf4j-api"
            }
        }
        externals.stream().limit(8).forEach(name ->
                out.add(new OnboardingRisk(RiskCategory.EXTERNAL_DEPENDENCY, "External reference: " + name,
                        "'" + name + "' is declared as a build dependency but is not a module in this "
                                + "repository - a third-party library to confirm.", List.of())));

        // Unresolved *code* references are a different thing: Code Atlas does not know
        // whether they point outside the repository or whether it simply could not
        // link them. Reporting them as external dependencies would be a guess, so they
        // are reported as what they are - an analysis gap.
        long codeUnresolved = unresolved.stream().filter(u -> !u.kind().equals("DEPENDS_ON")).count();
        if (codeUnresolved > 0) {
            String examples = unresolved.stream().filter(u -> !u.kind().equals("DEPENDS_ON"))
                    .map(Views.UnresolvedReference::targetName).distinct().sorted().limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            out.add(new OnboardingRisk(RiskCategory.ANALYSIS_LIMITATION, "Unresolved code references",
                    codeUnresolved + " reference(s) could not be linked to a target (e.g. " + examples
                            + "). These may point to libraries outside the repository, or be references "
                            + "Code Atlas could not resolve - the two are not distinguished.", List.of()));
        }

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
