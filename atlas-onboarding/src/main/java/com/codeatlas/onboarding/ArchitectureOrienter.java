package com.codeatlas.onboarding;

import com.codeatlas.agents.AgentAnswer;
import com.codeatlas.agents.AgentReport;
import com.codeatlas.model.Entity;
import com.codeatlas.onboarding.model.ArchitectureOrientation;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 5: structural orientation. This stage <em>reuses</em> the existing
 * Repository Orientation Agent - it runs the agent and restructures its already-
 * computed answers (main modules, most-central components, data stores, external
 * systems) into the onboarding shape, adding only a role-based layer inference.
 * It invents no new facts; architectural statements stay labelled as inferences.
 */
final class ArchitectureOrienter {

    private final AtlasToolApi api;

    ArchitectureOrienter(AtlasToolApi api) {
        this.api = api;
    }

    ArchitectureOrientation orient(AgentReport orientationReport) {
        List<String> majorModules = confirmed(orientationReport, "main modules", 8);
        List<String> mostConnected = confirmed(orientationReport, "most central", 5);
        List<String> dataAccess = confirmed(orientationReport, "data stores", 8);
        List<String> externalFacing = new ArrayList<>(confirmed(orientationReport, "external systems", 8));
        inferred(orientationReport, "external systems").forEach(externalFacing::add);

        List<String> layers = inferLayers();
        List<String> notes = new ArrayList<>();
        answer(orientationReport, "where should i start")
                .ifPresent(a -> notes.add("Orientation: " + a.answer()));
        answer(orientationReport, "read first")
                .ifPresent(a -> notes.add("Reading guidance: " + a.answer()));
        return new ArchitectureOrientation(majorModules, layers, mostConnected, dataAccess,
                externalFacing, notes);
    }

    /** Likely architectural layers, inferred from the roles the Java parser detected. */
    private List<String> inferLayers() {
        List<String> layers = new ArrayList<>();
        if (hasRole("controller") || !api.searchEntities("", "ENDPOINT", null, 1).value().isEmpty()) {
            layers.add("Presentation - controllers / REST endpoints (inferred)");
        }
        if (hasRole("service")) {
            layers.add("Service / orchestration (inferred)");
        }
        if (hasRole("repository") || hasJpaEntity()
                || !api.searchEntities("", "DATABASE_OBJECT", null, 1).value().isEmpty()) {
            layers.add("Persistence - repositories / entities / tables (inferred)");
        }
        if (!api.searchEntities("", "PACKAGE", "ada", 1).value().isEmpty()) {
            layers.add("Ada core - packages and package state (inferred)");
        }
        return layers;
    }

    private boolean hasRole(String role) {
        for (String kind : List.of("CLASS", "INTERFACE")) {
            for (Views.EntityView e : api.searchEntities("", kind, "java", CANDIDATE_CAP).value()) {
                if (role.equals(e.attributes().get(Entity.Attributes.ROLE))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasJpaEntity() {
        return api.searchEntities("", "CLASS", "java", CANDIDATE_CAP).value().stream()
                .anyMatch(e -> "true".equals(e.attributes().get(Entity.Attributes.JPA_ENTITY)));
    }

    private static List<String> confirmed(AgentReport report, String questionContains, int limit) {
        return answer(report, questionContains).map(a -> a.confirmedFacts().stream().limit(limit).toList())
                .orElse(List.of());
    }

    private static List<String> inferred(AgentReport report, String questionContains) {
        return answer(report, questionContains).map(AgentAnswer::inferredFindings).orElse(List.of());
    }

    private static java.util.Optional<AgentAnswer> answer(AgentReport report, String questionContains) {
        String needle = questionContains.toLowerCase(java.util.Locale.ROOT);
        return report.answers().stream()
                .filter(a -> a.question().toLowerCase(java.util.Locale.ROOT).contains(needle))
                .findFirst();
    }
}
