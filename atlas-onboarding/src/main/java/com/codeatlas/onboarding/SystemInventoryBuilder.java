package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.SystemInventory;
import com.codeatlas.tools.AtlasToolApi;
import com.codeatlas.tools.Views;

import java.util.ArrayList;
import java.util.List;

import static com.codeatlas.onboarding.OnboardingText.CANDIDATE_CAP;

/**
 * Stage 3: an inventory of what the repository contains - counts plus a few
 * representative examples per category, so a new developer sees both scale and
 * specifics. Every count is a real total (not the capped example list).
 */
final class SystemInventoryBuilder {

    private final AtlasToolApi api;

    SystemInventoryBuilder(AtlasToolApi api) {
        this.api = api;
    }

    SystemInventory build() {
        List<SystemInventory.Category> categories = new ArrayList<>();
        categories.add(category("Build modules", "MODULE", null));
        categories.add(category("Java packages", "PACKAGE", "java"));
        categories.add(union("Java classes & interfaces", "java", "CLASS", "INTERFACE", "RECORD", "ENUM"));
        categories.add(category("REST endpoints", "ENDPOINT", null));
        categories.add(category("Ada packages", "PACKAGE", "ada"));
        categories.add(union("Ada procedures & functions", "ada", "PROCEDURE", "FUNCTION"));
        categories.add(union("Ada tasks & protected types", "ada", "TASK", "PROTECTED_TYPE"));
        categories.add(category("Ada package state (variables)", "VARIABLE", "ada"));
        categories.add(category("Database tables", "DATABASE_OBJECT", null));
        categories.add(union("Data sources & sinks", null, "DATA_SOURCE", "DATA_SINK"));
        categories.add(category("Configuration files", "CONFIGURATION", null));
        categories.add(new SystemInventory.Category("Unresolved references",
                api.getUnresolvedReferences(1).totalMatches(),
                api.getUnresolvedReferences(5).value().stream()
                        .map(u -> u.fromId() + " -> " + u.targetName()).sorted().limit(5).toList()));
        return new SystemInventory(List.copyOf(categories));
    }

    private SystemInventory.Category category(String label, String kind, String language) {
        int count = api.searchEntities("", kind, language, CANDIDATE_CAP).totalMatches();
        List<String> examples = api.searchEntities("", kind, language, CANDIDATE_CAP).value().stream()
                .map(Views.EntityView::qualifiedName).distinct().sorted().limit(5).toList();
        return new SystemInventory.Category(label, count, examples);
    }

    private SystemInventory.Category union(String label, String language, String... kinds) {
        int count = 0;
        List<String> names = new ArrayList<>();
        for (String kind : kinds) {
            var r = api.searchEntities("", kind, language, CANDIDATE_CAP);
            count += r.totalMatches();
            r.value().forEach(e -> names.add(e.qualifiedName()));
        }
        return new SystemInventory.Category(label, count,
                names.stream().distinct().sorted().limit(5).toList());
    }
}
