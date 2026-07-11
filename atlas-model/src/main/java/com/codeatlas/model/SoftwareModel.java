package com.codeatlas.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    private final List<Relationship> relationships = Collections.synchronizedList(new ArrayList<>());

    // Lazily built adjacency indexes; null means "stale, rebuild on next read".
    private volatile Map<String, List<Relationship>> outgoing;
    private volatile Map<String, List<Relationship>> incoming;

    public void addEntity(Entity entity) {
        entities.put(entity.id(), entity);
        invalidateIndexes();
    }

    public void addEntities(Collection<Entity> toAdd) {
        for (Entity e : toAdd) {
            entities.put(e.id(), e);
        }
        invalidateIndexes();
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
