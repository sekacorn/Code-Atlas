package com.codeatlas.onboarding;

import com.codeatlas.model.Entity;
import com.codeatlas.onboarding.model.EntryPointCategory;
import com.codeatlas.onboarding.model.EntryPointSummary;
import com.codeatlas.onboarding.model.EvidenceRef;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;
import static com.codeatlas.onboarding.OnboardingText.ref;

/**
 * Stage 4: probable application entry points, discovered from structural markers
 * only - never from a method's name. Java: {@code main(String[])} methods and REST
 * endpoints. Ada: top-level parameterless library procedures (the GNAT main-unit
 * shape) and tasks. Everything is cited; naming-only guesses are excluded.
 */
final class EntryPointFinder {

    private final AtlasToolApi api;

    EntryPointFinder(AtlasToolApi api) {
        this.api = api;
    }

    List<EntryPointSummary> find() {
        List<EntryPointSummary> out = new ArrayList<>();
        // Build-declared mains come first: a build file declaring an entry point is
        // direct evidence, so those units must not also be reported as inferred.
        Set<String> declared = buildDeclaredMains(out);
        javaMains(out);
        endpoints(out);
        adaMains(out, declared);
        adaTasks(out);
        out.sort(Comparator.comparing(EntryPointSummary::language)
                .thenComparing(EntryPointSummary::type)
                .thenComparing(EntryPointSummary::stableId));
        return List.copyOf(out);
    }

    /**
     * Entry points a build file <em>declares</em> (e.g. a GNAT project's
     * {@code for Main use (...)}). This is the strongest entry-point evidence there
     * is — no naming or shape heuristic involved.
     */
    private Set<String> buildDeclaredMains(List<EntryPointSummary> out) {
        Set<String> declared = new TreeSet<>();
        for (Views.EntityView module : api.searchEntities("", "MODULE", null, CANDIDATE_CAP).value()) {
            String system = module.attributes().getOrDefault("buildSystem", "build");
            String buildFile = module.attributes().getOrDefault("buildFile", module.qualifiedName());
            for (Views.NeighborView n : api.getDependencies(module.stableId(), CANDIDATE_CAP).value()) {
                if (!n.edge().kind().equals("DECLARES_MAIN") || !declared.add(n.entity().stableId())) {
                    continue;
                }
                out.add(new EntryPointSummary(n.entity().stableId(), n.entity().qualifiedName(),
                        OnboardingText.languageOf(n.entity().stableId()), EntryPointCategory.MAIN,
                        "Build-declared main (" + system + ")", n.entity().location(),
                        List.of(new EvidenceRef(module.stableId(), n.edge().evidence()),
                                ref(n.entity())),
                        "High - declared as a main unit by " + buildFile, "DISCOVERED"));
            }
        }
        return declared;
    }

    private void javaMains(List<EntryPointSummary> out) {
        for (Views.EntityView m : api.searchEntities("main", "METHOD", "java", CANDIDATE_CAP).value()) {
            String sig = m.attributes().getOrDefault(Entity.Attributes.SIGNATURE, "");
            // Structural marker: the JVM entry signature, not merely the name "main".
            if (m.name().equals("main") && sig.startsWith("main(") && sig.contains("String")) {
                out.add(new EntryPointSummary(m.stableId(), m.qualifiedName(), "java",
                        EntryPointCategory.MAIN, "Java main method", m.location(), List.of(ref(m)),
                        "High - JVM main(String[]) signature", "DISCOVERED"));
            }
        }
    }

    private void endpoints(List<EntryPointSummary> out) {
        for (Views.EntityView e : api.searchEntities("", "ENDPOINT", null, CANDIDATE_CAP).value()) {
            List<EvidenceRef> evidence = new ArrayList<>();
            evidence.add(ref(e));
            api.getCallees(e.stableId(), 5).value().stream()
                    .filter(n -> n.edge().kind().equals("INVOKES"))
                    .findFirst()
                    .ifPresent(h -> evidence.add(new EvidenceRef(h.entity().stableId(), h.edge().evidence())));
            boolean pathUnresolved = Boolean.parseBoolean(
                    e.attributes().getOrDefault(Entity.Attributes.HTTP_PATH_UNRESOLVED, "false"));
            out.add(new EntryPointSummary(e.stableId(), e.name(), "java", EntryPointCategory.ENDPOINT,
                    "REST endpoint" + (pathUnresolved ? " (path not statically resolvable)" : ""),
                    e.location(), evidence, "High - HTTP-verb mapping annotation", "DISCOVERED"));
        }
    }

    private void adaMains(List<EntryPointSummary> out, Set<String> alreadyDeclared) {
        for (Views.EntityView p : api.searchEntities("", "PROCEDURE", "ada", CANDIDATE_CAP).value()) {
            if (alreadyDeclared.contains(p.stableId())) {
                continue; // the build declares it outright; no need to infer it
            }
            boolean parameterless = !p.attributes().containsKey(Entity.Attributes.PARAM_TYPES);
            boolean libraryLevel = !p.qualifiedName().contains("."); // not nested in a package
            // A top-level (library-level), parameterless procedure is the GNAT main-unit
            // shape. Such a procedure has no enclosing package; a package body helper
            // would carry a dotted qualified name and is therefore excluded.
            if (parameterless && libraryLevel) {
                out.add(new EntryPointSummary(p.stableId(), p.qualifiedName(), "ada",
                        EntryPointCategory.MAIN, "Ada main procedure (inferred)", p.location(),
                        List.of(ref(p)),
                        "Medium - top-level parameterless library procedure (GNAT main-unit shape)",
                        "INFERRED"));
            }
        }
    }

    private void adaTasks(List<EntryPointSummary> out) {
        for (Views.EntityView t : api.searchEntities("", "TASK", "ada", CANDIDATE_CAP).value()) {
            out.add(new EntryPointSummary(t.stableId(), t.qualifiedName(), "ada",
                    EntryPointCategory.TASK, "Ada task", t.location(), List.of(ref(t)),
                    "Medium - a task is a concurrent unit that runs independently", "DISCOVERED"));
        }
    }
}
