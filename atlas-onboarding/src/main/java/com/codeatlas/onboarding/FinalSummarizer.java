package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.ArchitectureOrientation;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.ExpertQuestion;
import com.codeatlas.onboarding.model.FinalSummary;
import com.codeatlas.onboarding.model.OnboardingRisk;
import com.codeatlas.onboarding.model.RepositoryIntake;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.onboarding.model.RiskCategory;
import com.codeatlas.onboarding.model.ScanHealthSummary;
import com.codeatlas.onboarding.model.SystemInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 12: the compact final summary. It answers the onboarding questions in
 * order and keeps confirmed facts, resolved relationships, inferred architecture,
 * unresolved questions and known limitations in separate buckets - so a reader can
 * always tell an inference from a fact.
 */
final class FinalSummarizer {

    FinalSummary summarize(RepositoryIntake intake, ScanHealthSummary health, SystemInventory inventory,
                           List<EntryPointSummary> entryPoints, ArchitectureOrientation orientation,
                           List<BoundarySummary> boundaries, List<RepresentativeLineagePath> paths,
                           List<CentralComponentSummary> central, List<OnboardingRisk> risks,
                           List<ExpertQuestion> questions) {
        List<FinalSummary.QA> qa = new ArrayList<>();
        qa.add(new FinalSummary.QA("What is this system?",
                intake.displayName() + " - " + String.join(", ", intake.languages()) + "; "
                        + intake.totalFiles() + " files; scan health " + health.status() + "."));
        qa.add(new FinalSummary.QA("Where does it start?", entryPointsPhrase(entryPoints)));
        qa.add(new FinalSummary.QA("What are the main Java components?",
                names(central, "java", "no central Java component ranked")));
        qa.add(new FinalSummary.QA("What are the main Ada components?",
                names(central, "ada", "no central Ada component ranked")));
        qa.add(new FinalSummary.QA("How do Java and Ada interact?", boundaryPhrase(boundaries)));
        qa.add(new FinalSummary.QA("What are the important data flows?", pathPhrase(paths)));
        qa.add(new FinalSummary.QA("Where is data stored?", storagePhrase(inventory)));
        qa.add(new FinalSummary.QA("What components should I understand first?",
                central.isEmpty() ? "none ranked"
                        : String.join(", ", central.stream().limit(5)
                        .map(CentralComponentSummary::displayName).toList())));
        qa.add(new FinalSummary.QA("What remains unresolved?",
                health.referencesUnresolved() + " unresolved reference(s); "
                        + count(risks, RiskCategory.EXTERNAL_DEPENDENCY) + " external dependency(ies)."));
        qa.add(new FinalSummary.QA("What should I ask the team?",
                questions.isEmpty() ? "no evidence-backed question was generated"
                        : questions.get(0).question()));

        List<String> confirmed = new ArrayList<>();
        entryPoints.stream().filter(e -> e.resolutionStatus().equals("DISCOVERED"))
                .forEach(e -> confirmed.add(e.type() + ": " + e.displayName()));
        inventory.categories().stream().filter(c -> c.count() > 0)
                .forEach(c -> confirmed.add(c.count() + " " + c.name().toLowerCase()));

        List<String> resolved = new ArrayList<>();
        boundaries.stream().filter(b -> b.resolutionStatus().equals("DISCOVERED"))
                .forEach(b -> resolved.add(b.javaSideLabel() + " <-> " + b.adaSideLabel()
                        + " (" + b.sharedArtifact() + ")"));
        paths.forEach(p -> resolved.add(p.title() + " [" + p.confidence() + "]"));

        List<String> inferred = new ArrayList<>(orientation.inferredLayers());
        boundaries.stream().filter(b -> !b.resolutionStatus().equals("DISCOVERED"))
                .forEach(b -> inferred.add("boundary (" + b.type() + "): " + b.javaSideLabel()
                        + " -> " + b.adaSideLabel() + " - " + b.resolutionStatus()));

        List<String> unresolved = new ArrayList<>();
        risks.stream().filter(r -> r.category() == RiskCategory.EXTERNAL_DEPENDENCY)
                .forEach(r -> unresolved.add(r.title()));
        paths.stream().filter(RepresentativeLineagePath::partial)
                .forEach(p -> unresolved.add("unresolved segment(s) on " + p.title()));

        List<String> limitations = risks.stream()
                .filter(r -> r.category() == RiskCategory.ANALYSIS_LIMITATION)
                .map(OnboardingRisk::description).toList();

        return new FinalSummary(qa, confirmed, resolved, inferred, unresolved, limitations);
    }

    private static String entryPointsPhrase(List<EntryPointSummary> entryPoints) {
        if (entryPoints.isEmpty()) {
            return "no entry point was detected within analyzed evidence";
        }
        return String.join("; ", entryPoints.stream().limit(4)
                .map(e -> e.displayName() + " (" + e.type() + ")").toList());
    }

    private static String names(List<CentralComponentSummary> central, String language, String empty) {
        List<String> ns = central.stream().filter(c -> c.language().equals(language))
                .map(CentralComponentSummary::displayName).limit(5).toList();
        return ns.isEmpty() ? empty : String.join(", ", ns);
    }

    private static String boundaryPhrase(List<BoundarySummary> boundaries) {
        if (boundaries.isEmpty()) {
            return "no Java/Ada boundary was detected from crossing evidence";
        }
        return boundaries.size() + " boundary(ies): " + String.join("; ", boundaries.stream().limit(3)
                .map(b -> b.type() + " (" + b.javaSideLabel() + " <-> " + b.adaSideLabel() + ")").toList());
    }

    private static String pathPhrase(List<RepresentativeLineagePath> paths) {
        if (paths.isEmpty()) {
            return "no representative data flow was sampled";
        }
        return String.join("; ", paths.stream().limit(3).map(RepresentativeLineagePath::title).toList());
    }

    private static String storagePhrase(SystemInventory inventory) {
        List<String> stores = new ArrayList<>();
        inventory.categories().stream()
                .filter(c -> (c.name().contains("Database tables") || c.name().contains("package state"))
                        && c.count() > 0)
                .forEach(c -> stores.add(c.count() + " " + c.name().toLowerCase()));
        return stores.isEmpty() ? "no modeled data store" : String.join(", ", stores);
    }

    private static int count(List<OnboardingRisk> risks, RiskCategory category) {
        return (int) risks.stream().filter(r -> r.category() == category).count();
    }
}
