package com.codeatlas.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The unified, in-memory software model: the merged output of every parser.
 *
 * <p>This is "the product" in the platform's terms &mdash; a searchable map of the
 * whole ecosystem. It maintains adjacency indexes so the analysis and graph
 * engines can answer reachability and dependency questions without rescanning.
 *
 * <p>Population is thread-safe (parsers run in parallel); the derived adjacency
 * indexes are rebuilt lazily and cached until the next mutation.
 */
public final class SoftwareModel {

    // Entity kinds that legitimately aggregate across files (packages span files;
    // FILE/PROJECT are re-added with enriched attributes).
    private static final Set<EntityKind> AGGREGATING =
            EnumSet.of(EntityKind.FILE, EntityKind.PROJECT, EntityKind.PACKAGE, EntityKind.NAMESPACE);

    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    private final List<Relationship> relationships = Collections.synchronizedList(new ArrayList<>());
    private final List<Diagnostic> diagnostics = Collections.synchronizedList(new ArrayList<>());

    // Lazily built adjacency indexes; null means "stale, rebuild on next read".
    private volatile Map<String, List<Relationship>> outgoing;
    private volatile Map<String, List<Relationship>> incoming;

    /**
     * Adds an entity, merging it into any existing entity that shares its stable id.
     * Legitimate merges (package aggregation, Ada spec/body, idempotent re-adds)
     * combine attributes and evidence; a genuine collision between two distinct
     * declarations is recorded as a {@link Diagnostic} and never silently overwritten.
     */
    public void addEntity(Entity entity) {
        entities.compute(entity.id(), (id, existing) -> {
            if (existing == null) {
                return entity;
            }
            if (isMergeable(existing, entity)) {
                return Entity.merge(existing, entity);
            }
            diagnostics.add(new Diagnostic(Diagnostic.STABLE_ID_COLLISION,
                    "Two distinct declarations share id '" + id + "': "
                            + locationOf(existing) + " and " + locationOf(entity)
                            + " (kept the first; both retained as evidence)"));
            return existing;
        });
        invalidateIndexes();
    }

    public void addEntities(Collection<Entity> toAdd) {
        for (Entity e : toAdd) {
            addEntity(e);
        }
    }

    /**
     * Decides whether two entities sharing a stable id are the same logical entity
     * (mergeable) rather than an accidental collision between distinct declarations.
     */
    private static boolean isMergeable(Entity a, Entity b) {
        if (a.kind() != b.kind()) {
            return false;
        }
        if (AGGREGATING.contains(a.kind())) {
            return true;
        }
        if (sameDeclarationSite(a, b)) {
            return true; // idempotent re-add of the same declaration
        }
        // Ada specification/body pair: same id, opposite parts.
        String pa = a.attributes().get(Entity.Attributes.ADA_PART);
        String pb = b.attributes().get(Entity.Attributes.ADA_PART);
        if (pb != null) {
            if (pa != null && !pa.equals(pb)) {
                return true;
            }
            // existing may already carry the complementary part from an earlier merge
            boolean needSpec = pb.equals("spec") && !a.boolAttribute(Entity.Attributes.HAS_SPEC, false);
            boolean needBody = pb.equals("body") && !a.boolAttribute(Entity.Attributes.HAS_BODY, false);
            if ((needSpec || needBody) && (a.boolAttribute(Entity.Attributes.HAS_SPEC, false)
                    || a.boolAttribute(Entity.Attributes.HAS_BODY, false))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameDeclarationSite(Entity a, Entity b) {
        var la = a.location();
        var lb = b.location();
        return la.isPresent() && lb.isPresent()
                && la.get().filePath().equals(lb.get().filePath())
                && la.get().startLine() == lb.get().startLine();
    }

    private static String locationOf(Entity e) {
        return e.location().map(SourceLocation::toString).orElse("unknown");
    }

    public List<Diagnostic> diagnostics() {
        synchronized (diagnostics) {
            return List.copyOf(diagnostics);
        }
    }

    public void addRelationship(Relationship relationship) {
        relationships.add(relationship);
        invalidateIndexes();
    }

    public void addRelationships(Collection<Relationship> toAdd) {
        relationships.addAll(toAdd);
        invalidateIndexes();
    }

    private void invalidateIndexes() {
        outgoing = null;
        incoming = null;
    }

    public Optional<Entity> entity(String id) {
        return Optional.ofNullable(entities.get(id));
    }

    public Collection<Entity> entities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    public List<Entity> entitiesOfKind(EntityKind kind) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : entities.values()) {
            if (e.kind() == kind) {
                out.add(e);
            }
        }
        return out;
    }

    public List<Relationship> relationships() {
        synchronized (relationships) {
            return List.copyOf(relationships);
        }
    }

    public int entityCount() {
        return entities.size();
    }

    public int relationshipCount() {
        synchronized (relationships) {
            return relationships.size();
        }
    }

    public Map<EntityKind, Integer> entityCountsByKind() {
        Map<EntityKind, Integer> counts = new EnumMap<>(EntityKind.class);
        for (Entity e : entities.values()) {
            counts.merge(e.kind(), 1, Integer::sum);
        }
        return counts;
    }

    /** Edges whose source is {@code entityId}. */
    public List<Relationship> outgoing(String entityId) {
        return outgoingIndex().getOrDefault(entityId, List.of());
    }

    /** Edges whose target is {@code entityId} (only resolved edges are indexed here). */
    public List<Relationship> incoming(String entityId) {
        return incomingIndex().getOrDefault(entityId, List.of());
    }

    private Map<String, List<Relationship>> outgoingIndex() {
        Map<String, List<Relationship>> local = outgoing;
        if (local == null) {
            local = buildIndex(true);
            outgoing = local;
        }
        return local;
    }

    private Map<String, List<Relationship>> incomingIndex() {
        Map<String, List<Relationship>> local = incoming;
        if (local == null) {
            local = buildIndex(false);
            incoming = local;
        }
        return local;
    }

    private Map<String, List<Relationship>> buildIndex(boolean byFrom) {
        Map<String, List<Relationship>> index = new LinkedHashMap<>();
        synchronized (relationships) {
            for (Relationship r : relationships) {
                String key = byFrom ? r.fromId() : r.toId();
                index.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        return index;
    }
}
