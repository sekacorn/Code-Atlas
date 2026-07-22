package com.codeatlas.onboarding.model;

import java.util.List;

/**
 * Stage 3 output: an inventory of what the repository contains, as ordered
 * categories each carrying a count and a few representative examples. Counts and
 * examples are both shown so a new developer sees both scale and specifics.
 */
public record SystemInventory(List<Category> categories) {

    public SystemInventory {
        categories = List.copyOf(categories);
    }

    /**
     * One inventory line.
     *
     * @param name     category label (e.g. "Java packages", "Ada procedures")
     * @param count    total number found
     * @param examples up to a handful of representative names, sorted
     */
    public record Category(String name, int count, List<String> examples) {
        public Category {
            examples = List.copyOf(examples);
        }
    }
}
