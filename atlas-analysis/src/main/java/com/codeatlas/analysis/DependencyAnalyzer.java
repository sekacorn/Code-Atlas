package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lifts the fine-grained entity graph to a component (package) dependency graph and
 * reports coupling and cycles.
 *
 * <p>Each entity is attributed to its enclosing package by following {@code CONTAINS}
 * edges; usage edges between entities in different packages become package-to-package
 * dependencies. Circular dependencies are found with a depth-first search.
 */
public final class DependencyAnalyzer {

    private static final Set<RelationshipKind> USAGE = EnumSet.of(
            RelationshipKind.CALLS, RelationshipKind.REFERENCES, RelationshipKind.INHERITS,
            RelationshipKind.IMPLEMENTS, RelationshipKind.INSTANTIATES, RelationshipKind.USES,
            RelationshipKind.DEPENDS_ON);

    public DependencyAnalysis analyze(SoftwareModel model) {
        Map<String, String> entityToPackage = mapEntitiesToPackages(model);

        // packageQn -> set of packageQn it depends on
        Map<String, Set<String>> deps = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (Entity p : model.entitiesOfKind(EntityKind.PACKAGE)) {
            deps.putIfAbsent(p.qualifiedName(), new LinkedHashSet<>());
            dependents.putIfAbsent(p.qualifiedName(), new LinkedHashSet<>());
        }

        for (Relationship r : model.relationships()) {
            if (!r.resolved() || !USAGE.contains(r.kind())) {
                continue;
            }
            String from = entityToPackage.get(r.fromId());
            String to = entityToPackage.get(r.toId());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            deps.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            dependents.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);
        }

        List<ComponentDependency> components = new ArrayList<>();
        for (String pkg : deps.keySet()) {
            int d = deps.get(pkg).size();
            int in = dependents.getOrDefault(pkg, Set.of()).size();
            components.add(new ComponentDependency(pkg, d, in,
                    List.copyOf(deps.get(pkg)), ComponentDependency.riskOf(d, in)));
        }
        components.sort(Comparator.comparingInt((ComponentDependency c) -> c.dependencies() + c.dependents())
                .reversed().thenComparing(ComponentDependency::name));

        return new DependencyAnalysis(components, findCycles(deps));
    }

    /** Attributes every entity to its enclosing package via CONTAINS reachability. */
    private Map<String, String> mapEntitiesToPackages(SoftwareModel model) {
        Map<String, String> result = new HashMap<>();
        for (Entity pkg : model.entitiesOfKind(EntityKind.PACKAGE)) {
            Deque<String> stack = new ArrayDeque<>();
            stack.push(pkg.id());
            Set<String> seen = new HashSet<>();
            while (!stack.isEmpty()) {
                String id = stack.pop();
                if (!seen.add(id)) {
                    continue;
                }
                // First package to claim an entity wins (nearest enclosing).
                result.putIfAbsent(id, pkg.qualifiedName());
                for (Relationship out : model.outgoing(id)) {
                    if (out.kind() == RelationshipKind.CONTAINS) {
                        Entity child = model.entity(out.toId()).orElse(null);
                        // Do not cross into a nested package; it maps to itself.
                        if (child != null && child.kind() == EntityKind.PACKAGE && !child.id().equals(pkg.id())) {
                            continue;
                        }
                        stack.push(out.toId());
                    }
                }
            }
        }
        return result;
    }

    /** Depth-first cycle detection over the package dependency graph. */
    private List<List<String>> findCycles(Map<String, Set<String>> deps) {
        List<List<String>> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        Set<String> reported = new HashSet<>();
        for (String node : deps.keySet()) {
            dfs(node, deps, visited, onStack, path, cycles, reported);
        }
        return cycles;
    }

    private void dfs(String node, Map<String, Set<String>> deps, Set<String> visited,
                     Set<String> onStack, Deque<String> path, List<List<String>> cycles,
                     Set<String> reported) {
        if (onStack.contains(node)) {
            List<String> cycle = new ArrayList<>();
            boolean collecting = false;
            for (String n : path) {
                if (n.equals(node)) {
                    collecting = true;
                }
                if (collecting) {
                    cycle.add(n);
                }
            }
            cycle.add(node);
            String key = new java.util.TreeSet<>(cycle).toString();
            if (cycle.size() > 1 && reported.add(key)) {
                cycles.add(cycle);
            }
            return;
        }
        if (!visited.add(node)) {
            return;
        }
        onStack.add(node);
        path.addLast(node);
        for (String next : deps.getOrDefault(node, Set.of())) {
            dfs(next, deps, visited, onStack, path, cycles, reported);
        }
        path.removeLast();
        onStack.remove(node);
    }
}
