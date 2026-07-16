package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.CentralComponentSummary;
import com.codeatlas.onboarding.model.EntryPointCategory;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.ExpertQuestion;
import com.codeatlas.onboarding.model.RepresentativeLineagePath;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 11: evidence-backed questions for subject-matter experts. Every question
 * names real components and cites real evidence, and is only generated for a role
 * the repository's evidence actually supports - no Ada-specialist question when
 * there is no Ada, no question about a component that does not exist. Resolved
 * facts are stated as facts; only the genuinely-unknown part is asked.
 */
final class ExpertQuestionGenerator {

    private final AtlasToolApi api;

    ExpertQuestionGenerator(AtlasToolApi api) {
        this.api = api;
    }

    List<ExpertQuestion> generate(List<BoundarySummary> boundaries,
                                  List<CentralComponentSummary> central,
                                  List<RepresentativeLineagePath> paths,
                                  List<EntryPointSummary> entryPoints) {
        List<ExpertQuestion> out = new ArrayList<>();

        // Technical lead + systems engineer: only when a boundary was found.
        if (!boundaries.isEmpty()) {
            BoundarySummary b = boundaries.get(0);
            List<String> ids = b.adaSideId().isEmpty()
                    ? List.of(b.javaSideId()) : List.of(b.javaSideId(), b.adaSideId());
            String across = b.adaSideId().isEmpty() ? "a non-Java component" : b.adaSideLabel();
            out.add(new ExpertQuestion("Technical lead",
                    "Code Atlas found that " + b.javaSideLabel() + " reaches " + across + " via "
                            + b.sharedArtifact() + " (" + b.type() + "). Is this the active production "
                            + "integration path, or is another adapter selected through configuration?",
                    ids, b.evidence(), ExpertQuestion.BASIS_UNRESOLVED));
            out.add(new ExpertQuestion("Systems engineer",
                    "The Java and Ada sides are integrated via " + b.type() + " (" + b.sharedArtifact()
                            + "). How are the two deployed, and how is that boundary configured at runtime?",
                    ids, b.evidence(), ExpertQuestion.BASIS_UNRESOLVED));
        }

        // Java specialist: only when there is a central Java component.
        central.stream().filter(c -> c.language().equals("java")).findFirst().ifPresent(c ->
                out.add(new ExpertQuestion("Java specialist",
                        "Code Atlas ranks " + c.displayName() + " as central (" + c.scoreBasis()
                                + " - resolved counts). Is it accessed concurrently, and what is the safe "
                                + "way to change it?",
                        List.of(c.stableId()), List.of(EvidenceRef.of(c.stableId())),
                        ExpertQuestion.BASIS_CONFIRMED)));

        // Ada specialist: only when Ada is present.
        adaFocus().ifPresent(a -> out.add(a));

        // Database engineer: only when a table exists.
        databaseQuestion().ifPresent(out::add);

        // Cybersecurity engineer: only when external input exists (endpoint or source).
        securityQuestion(entryPoints).ifPresent(out::add);

        // Test engineer: only when a representative flow was sampled.
        if (!paths.isEmpty()) {
            RepresentativeLineagePath p = paths.get(0);
            out.add(new ExpertQuestion("Test engineer",
                    "The representative data flow '" + p.title() + "' crosses several components. Is this "
                            + "flow covered by an automated test, and which edge cases are known to be risky?",
                    List.of(p.startId(), p.endId()),
                    List.of(EvidenceRef.of(p.startId()), EvidenceRef.of(p.endId())),
                    ExpertQuestion.BASIS_UNRESOLVED));
        }
        return List.copyOf(out);
    }

    private java.util.Optional<ExpertQuestion> adaFocus() {
        // Prefer an Ada package that holds state; otherwise the largest Ada package.
        List<Views.EntityView> state = api.searchEntities("", "VARIABLE", "ada", CANDIDATE_CAP).value();
        if (!state.isEmpty()) {
            Views.EntityView v = state.stream()
                    .min(java.util.Comparator.comparing(Views.EntityView::stableId)).orElseThrow();
            return java.util.Optional.of(new ExpertQuestion("Ada specialist",
                    "The Ada side keeps package state " + v.qualifiedName() + ". Is this the intended "
                            + "system-of-record for that data, or is it a cache in front of a database?",
                    List.of(v.stableId()), List.of(OnboardingText.ref(v)),
                    ExpertQuestion.BASIS_UNRESOLVED));
        }
        List<Views.EntityView> pkgs = api.searchEntities("", "PACKAGE", "ada", CANDIDATE_CAP).value();
        if (!pkgs.isEmpty()) {
            Views.EntityView p = pkgs.stream()
                    .min(java.util.Comparator.comparing(Views.EntityView::stableId)).orElseThrow();
            return java.util.Optional.of(new ExpertQuestion("Ada specialist",
                    "What is the responsibility of the Ada package " + p.qualifiedName()
                            + ", and which of its subprograms are the intended external entry points?",
                    List.of(p.stableId()), List.of(OnboardingText.ref(p)),
                    ExpertQuestion.BASIS_UNRESOLVED));
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<ExpertQuestion> databaseQuestion() {
        List<Views.EntityView> tables = api.searchEntities("", "DATABASE_OBJECT", null, CANDIDATE_CAP).value();
        if (tables.isEmpty()) {
            return java.util.Optional.empty();
        }
        Views.EntityView t = tables.stream()
                .min(java.util.Comparator.comparing(Views.EntityView::stableId)).orElseThrow();
        List<Views.NeighborView> touchers = api.getDependents(t.stableId(), 20).value();
        String writer = touchers.stream().filter(n -> n.edge().kind().equals("WRITES_TO")
                        || n.edge().kind().equals("PERSISTS_TO") || n.edge().kind().equals("MAPS_TO"))
                .map(n -> n.entity().qualifiedName()).sorted().findFirst().orElse("an unresolved writer");
        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(OnboardingText.ref(t));
        return java.util.Optional.of(new ExpertQuestion("Database engineer",
                "The table '" + t.name() + "' is mapped from " + writer + ". Which component owns its "
                        + "schema, and how is concurrent access coordinated across the system?",
                List.of(t.stableId()), evidence, ExpertQuestion.BASIS_UNRESOLVED));
    }

    private java.util.Optional<ExpertQuestion> securityQuestion(List<EntryPointSummary> entryPoints) {
        // Prefer an HTTP endpoint; otherwise a detected external input source.
        java.util.Optional<EntryPointSummary> endpoint = entryPoints.stream()
                .filter(e -> e.category() == EntryPointCategory.ENDPOINT).findFirst();
        if (endpoint.isPresent()) {
            EntryPointSummary e = endpoint.get();
            return java.util.Optional.of(new ExpertQuestion("Cybersecurity engineer",
                    "External requests enter at " + e.displayName() + ". Where is that input validated "
                            + "and authorized before it reaches the service and persistence layers?",
                    List.of(e.stableId()), e.evidence(), ExpertQuestion.BASIS_UNRESOLVED));
        }
        List<Views.EntityView> sources = api.getDataSources().value();
        if (sources.isEmpty()) {
            return java.util.Optional.empty();
        }
        Views.EntityView s = sources.get(0);
        return java.util.Optional.of(new ExpertQuestion("Cybersecurity engineer",
                "External input enters at " + s.name() + ". Where is that input validated, and what "
                        + "trust boundary applies before it is processed?",
                List.of(s.stableId()), List.of(OnboardingText.ref(s)), ExpertQuestion.BASIS_UNRESOLVED));
    }
}
