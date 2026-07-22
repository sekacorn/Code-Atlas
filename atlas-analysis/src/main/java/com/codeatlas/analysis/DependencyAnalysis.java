package com.codeatlas.analysis;

import java.util.List;

/**
 * Result of dependency analysis: per-component coupling plus any circular
 * dependencies found between components.
 *
 * @param components   coupling summary per component, most-coupled first
 * @param circular     component pairs/cycles that depend on each other
 */
public record DependencyAnalysis(List<ComponentDependency> components,
                                 List<List<String>> circular) {

    public DependencyAnalysis {
        components = List.copyOf(components);
        circular = circular.stream().map(List::copyOf).toList();
    }

    @Override
    public List<List<String>> circular() {
        return circular.stream().map(List::copyOf).toList();
    }
}
