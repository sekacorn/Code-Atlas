package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the <em>unresolved</em> relationships that parsers emit (method calls,
 * supertypes, imports, instantiations) into concrete edges between model entities.
 *
 * <p>Parsers run per-file and cannot see other files, so they record a symbolic
 * target name. Once every file is parsed, the Linker matches those names against
 * the merged model and adds resolved edges. It is intentionally conservative:
 * <ul>
 *   <li>overloaded/ambiguous names resolve to <em>all</em> plausible targets, so
 *       downstream dead-code detection never wrongly assumes something is unused;</li>
 *   <li>names that are too ambiguous (more than {@value #MAX_CANDIDATES} matches)
 *       are left unresolved rather than guessed;</li>
 *   <li>original unresolved edges are preserved for auditability.</li>
 * </ul>
 */
public final class Linker {

    private static final Logger log = LoggerFactory.getLogger(Linker.class);
    private static final int MAX_CANDIDATES = 8;

    private static final Set<EntityKind> CALLABLES =
            EnumSet.of(EntityKind.METHOD, EntityKind.CONSTRUCTOR, EntityKind.FUNCTION, EntityKind.PROCEDURE);
    private static final Set<EntityKind> TYPES =
            EnumSet.of(EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM, EntityKind.RECORD, EntityKind.TYPE);

    public LinkStats link(SoftwareModel model) {
        Map<String, List<Entity>> callablesByName = new HashMap<>();
        Map<String, List<Entity>> typesByName = new HashMap<>();
        Map<String, List<Entity>> byQualifiedName = new HashMap<>();

        for (Entity e : model.entities()) {
            byQualifiedName.computeIfAbsent(e.qualifiedName().toLowerCase(), k -> new ArrayList<>()).add(e);
            if (CALLABLES.contains(e.kind())) {
                callablesByName.computeIfAbsent(e.name().toLowerCase(), k -> new ArrayList<>()).add(e);
            } else if (TYPES.contains(e.kind())) {
                typesByName.computeIfAbsent(e.name().toLowerCase(), k -> new ArrayList<>()).add(e);
            }
        }

        List<Relationship> resolved = new ArrayList<>();
        int edgesAdded = 0;
        int refResolved = 0;
        int refUnresolved = 0;
        int refAmbiguous = 0;
        for (Relationship r : model.relationships()) {
            if (r.resolved()) {
                continue;
            }
            // Kinds outside this switch (e.g. parser state candidates) are handled
            // by their own analyzers and must not skew the linker's reference stats.
            List<Entity> targets = switch (r.kind()) {
                case CALLS -> resolveCall(r, callablesByName);
                case INHERITS, IMPLEMENTS, INSTANTIATES, IMPORTS, RENAMES, REFERENCES ->
                        resolveType(r, typesByName, byQualifiedName);
                default -> null;
            };
            if (targets == null) {
                continue;
            }
            if (targets.isEmpty()) {
                refUnresolved++;
                continue;
            }
            if (targets.size() > MAX_CANDIDATES) {
                refAmbiguous++;
                continue;
            }
            refResolved++;
            for (Entity target : targets) {
                if (!target.id().equals(r.fromId())) {
                    resolved.add(Relationship.builder(r.kind(), r.fromId(), target.id())
                            .resolved(true).build());
                    edgesAdded++;
                }
            }
        }
        model.addRelationships(resolved);
        log.info("Linker resolved {} references into {} edges ({} unresolved, {} ambiguous)",
                refResolved, edgesAdded, refUnresolved, refAmbiguous);
        return new LinkStats(refResolved, refUnresolved, refAmbiguous);
    }

    private List<Entity> resolveCall(Relationship r, Map<String, List<Entity>> callablesByName) {
        String name = r.attributes().getOrDefault("callName", r.toId()).toLowerCase();
        List<Entity> candidates = callablesByName.get(name);
        if (candidates == null) {
            return List.of();
        }
        String argCountStr = r.attributes().get("argCount");
        if (argCountStr == null) {
            return candidates; // Ada: no arity info, match by name
        }
        int argCount = Integer.parseInt(argCountStr);
        List<Entity> byArity = candidates.stream()
                .filter(c -> paramCount(c) < 0 || paramCount(c) == argCount)
                .toList();
        return byArity.isEmpty() ? candidates : byArity;
    }

    private List<Entity> resolveType(Relationship r, Map<String, List<Entity>> typesByName,
                                     Map<String, List<Entity>> byQualifiedName) {
        String typeName = r.attributes().getOrDefault("typeName", r.toId());
        String key = typeName.toLowerCase();
        List<Entity> byQn = byQualifiedName.get(key);
        if (byQn != null) {
            List<Entity> onlyTypes = byQn.stream().filter(e -> TYPES.contains(e.kind())).toList();
            if (!onlyTypes.isEmpty()) {
                return onlyTypes;
            }
        }
        String simple = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        List<Entity> bySimple = typesByName.get(simple);
        return bySimple != null ? bySimple : List.of();
    }

    /** Parameter count parsed from a callable's signature attribute, or -1 if unknown. */
    private int paramCount(Entity callable) {
        String sig = callable.attribute(Entity.Attributes.SIGNATURE).orElse(null);
        if (sig == null) {
            return -1;
        }
        int open = sig.indexOf('(');
        int close = sig.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return open >= 0 && close == open + 1 ? 0 : -1;
        }
        String params = sig.substring(open + 1, close).trim();
        if (params.isEmpty()) {
            return 0;
        }
        return (int) params.chars().filter(c -> c == ',').count() + 1;
    }
}
