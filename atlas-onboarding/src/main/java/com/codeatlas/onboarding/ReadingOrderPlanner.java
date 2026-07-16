package com.codeatlas.onboarding;

import com.codeatlas.model.Entity;
import com.codeatlas.onboarding.model.BoundarySummary;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.onboarding.model.ReadingRecommendation;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 10: a deterministic suggested reading order, adapted to the evidence
 * actually present. The template runs build files -> entry points -> public
 * interfaces -> controllers -> services -> the Java/Ada boundary -> core Ada packages
 * -> persistence/state -> output -> tests. Each recommendation states why it matters
 * and what question it answers; nothing is recommended twice.
 */
final class ReadingOrderPlanner {

    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "build.xml", "makefile", "cmakelists.txt");

    private final AtlasToolApi api;

    ReadingOrderPlanner(AtlasToolApi api) {
        this.api = api;
    }

    List<ReadingRecommendation> plan(List<EntryPointSummary> entryPoints, List<BoundarySummary> boundaries) {
        List<ReadingRecommendation> out = new ArrayList<>();
        Set<String> seen = new TreeSet<>();

        // 1. Build / project definitions.
        for (Views.EntityView f : buildFiles()) {
            add(out, seen, f.stableId(), f.qualifiedName(),
                    "Build/project definition - shows modules, dependencies and how the system is assembled.",
                    "How is the system built and what are its dependencies?", List.of(),
                    List.of(OnboardingText.ref(f)), "High - a build/project file");
        }
        // 2. Application entry points.
        for (EntryPointSummary e : entryPoints) {
            add(out, seen, e.stableId(), e.displayName(),
                    "Application entry point (" + e.type() + ") - where execution begins.",
                    "How is the system started / invoked?", buildFilePrereq(),
                    e.evidence(), e.confidence());
        }
        // 3. Public interfaces (contracts).
        for (Views.EntityView i : sorted(api.searchEntities("", "INTERFACE", "java", CANDIDATE_CAP).value())) {
            add(out, seen, i.stableId(), i.qualifiedName(),
                    "Public interface - a contract that decouples callers from implementations.",
                    "What contracts do components depend on?", List.of(),
                    List.of(OnboardingText.ref(i)), "Medium - a declared interface");
        }
        // 4. Controllers / input handlers.
        for (Views.EntityView c : byRole("controller")) {
            add(out, seen, c.stableId(), c.qualifiedName(),
                    "Controller / input handler - receives external requests and starts the primary flow.",
                    "How does an external request enter the system?", entryPrereq(),
                    List.of(OnboardingText.ref(c)), "High - role=controller");
        }
        // 5. Services / orchestration.
        for (Views.EntityView s : byRole("service")) {
            add(out, seen, s.stableId(), s.qualifiedName(),
                    "Service / orchestration - the business logic the controller drives.",
                    "Where is the core processing performed?", List.of("the controller/entry point"),
                    List.of(OnboardingText.ref(s)), "High - role=service");
        }
        // 6. Java/Ada boundary components.
        for (BoundarySummary b : boundaries) {
            add(out, seen, b.javaSideId(), b.javaSideLabel(),
                    "Java/Ada boundary (" + b.type() + ") - where control/data crosses to the Ada side.",
                    "How do the Java and Ada sides interact?", List.of("the service layer"),
                    b.evidence(), b.confidence());
            if (!b.adaSideId().isEmpty()) {
                add(out, seen, b.adaSideId(), b.adaSideLabel(),
                        "Ada counterpart of the boundary - the far side of the integration.",
                        "What does the Ada side do when called across the boundary?",
                        List.of("the Java boundary adapter"), b.evidence(), b.confidence());
            }
        }
        // 7. Core Ada packages.
        for (Views.EntityView p : topAdaPackages()) {
            add(out, seen, p.stableId(), p.qualifiedName(),
                    "Core Ada package - a primary unit of the Ada side.",
                    "How is the Ada side structured?", List.of("the Java/Ada boundary"),
                    List.of(OnboardingText.ref(p)), "Medium - a top Ada package by size");
        }
        // 8. State & persistence.
        for (Views.EntityView t : sorted(api.searchEntities("", "DATABASE_OBJECT", null, CANDIDATE_CAP).value())) {
            add(out, seen, t.stableId(), t.name(),
                    "Data store - a table the system reads/writes.",
                    "Where is state persisted?", List.of("the service/repository layer"),
                    List.of(OnboardingText.ref(t)), "High - a mapped table");
        }
        for (Views.EntityView v : sorted(api.searchEntities("", "VARIABLE", "ada", CANDIDATE_CAP).value())) {
            add(out, seen, v.stableId(), v.qualifiedName(),
                    "Ada package state - shared mutable state on the Ada side.",
                    "Where does the Ada side keep its state?", List.of("the core Ada packages"),
                    List.of(OnboardingText.ref(v)), "Medium - package-level state");
        }
        // 9. Output / reporting.
        for (Views.EntityView s : sorted(api.searchEntities("", "DATA_SINK", null, CANDIDATE_CAP).value())) {
            add(out, seen, s.stableId(), s.name(),
                    "Output sink - where results leave the system.",
                    "Where do results go?", List.of("the processing layer"),
                    List.of(OnboardingText.ref(s)), "Medium - a detected output sink");
        }
        // 10. Tests for the primary flow.
        for (Views.EntityView f : testFiles()) {
            add(out, seen, f.stableId(), f.qualifiedName(),
                    "Test for the primary flow - an executable specification of expected behavior.",
                    "How is the primary flow expected to behave?", List.of("the primary flow components"),
                    List.of(OnboardingText.ref(f)), "Medium - a test file");
        }
        return List.copyOf(out);
    }

    // ---- helpers ----

    private void add(List<ReadingRecommendation> out, Set<String> seen, String id, String name,
                     String reason, String question, List<String> prereqs, List<EvidenceRef> evidence,
                     String confidence) {
        if (id == null || id.isEmpty() || !seen.add(id)) {
            return;
        }
        out.add(new ReadingRecommendation(out.size() + 1, id, name, reason, question,
                prereqs, evidence, confidence));
    }

    private List<Views.EntityView> buildFiles() {
        return api.searchEntities("", "FILE", null, CANDIDATE_CAP).value().stream()
                .filter(f -> BUILD_FILES.contains(f.name().toLowerCase(Locale.ROOT))
                        || f.name().toLowerCase(Locale.ROOT).endsWith(".gpr"))
                .sorted(Comparator.comparing(Views.EntityView::qualifiedName)).toList();
    }

    private List<Views.EntityView> testFiles() {
        return api.searchEntities("", "FILE", null, CANDIDATE_CAP).value().stream()
                .filter(f -> {
                    String p = f.qualifiedName().toLowerCase(Locale.ROOT);
                    String n = f.name().toLowerCase(Locale.ROOT);
                    return p.contains("/test/") || p.contains("\\test\\")
                            || n.endsWith("test.java") || n.endsWith("tests.java") || n.contains("_test");
                })
                .sorted(Comparator.comparing(Views.EntityView::qualifiedName)).limit(4).toList();
    }

    private List<Views.EntityView> byRole(String role) {
        List<Views.EntityView> out = new ArrayList<>();
        for (Views.EntityView c : api.searchEntities("", "CLASS", "java", CANDIDATE_CAP).value()) {
            if (role.equals(c.attributes().get(Entity.Attributes.ROLE))) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(Views.EntityView::stableId));
        return out;
    }

    private List<Views.EntityView> topAdaPackages() {
        return api.searchEntities("", "PACKAGE", "ada", CANDIDATE_CAP).value().stream()
                .sorted(Comparator.comparingInt((Views.EntityView p) ->
                                api.getMembers(p.stableId(), 1).totalMatches()).reversed()
                        .thenComparing(Views.EntityView::stableId))
                .limit(4).toList();
    }

    private static List<Views.EntityView> sorted(List<Views.EntityView> in) {
        return in.stream().sorted(Comparator.comparing(Views.EntityView::stableId)).limit(6).toList();
    }

    private static List<String> buildFilePrereq() {
        return List.of("the build/project files");
    }

    private static List<String> entryPrereq() {
        return List.of("the application entry points");
    }
}
