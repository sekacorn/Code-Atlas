package com.codeatlas.tools;

import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.analysis.ComplexityHotspot;
import com.codeatlas.analysis.DeadCodeCandidate;
import com.codeatlas.analysis.lineage.LineageQuery;
import com.codeatlas.analysis.lineage.LineageResult;
import com.codeatlas.analysis.lineage.LineageService;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.index.ScanRecord;
import com.codeatlas.model.Diagnostic;
import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The controlled, read-only investigation API over a repository's persisted index
 * — the boundary through which future agents (and scripts) query Code Atlas.
 *
 * <p>Guarantees, by construction:
 * <ul>
 *   <li><b>Read-only:</b> the underlying store is opened with database-level
 *       read-only access; this class exposes no mutating operation and never
 *       hands out the mutable model.</li>
 *   <li><b>No repository access:</b> the API is constructed from an index path
 *       only — it never learns where the analyzed repository lives, so it cannot
 *       touch its files.</li>
 *   <li><b>Evidence-first:</b> every result carries stable ids, source locations,
 *       rule ids, confidence and resolution status where applicable, plus the
 *       scan id and truncation info in the {@link ToolResult} envelope.</li>
 *   <li><b>Honest capability:</b> operations the platform cannot answer yet
 *       (build membership, configuration references) return
 *       {@code supported=false} with the reason — never a silent empty list.</li>
 * </ul>
 *
 * <p>Deterministic: identical index content yields identical results, including
 * ordering. Dead-code/complexity views are computed with the default analysis
 * thresholds documented in AGENTS.md.
 */
public final class AtlasToolApi implements AutoCloseable {

    /** Edge kinds that mean "the source uses the target". */
    private static final Set<RelationshipKind> USAGE = EnumSet.of(
            RelationshipKind.CALLS, RelationshipKind.INVOKES, RelationshipKind.REFERENCES,
            RelationshipKind.INHERITS, RelationshipKind.IMPLEMENTS, RelationshipKind.INSTANTIATES,
            RelationshipKind.USES, RelationshipKind.IMPORTS, RelationshipKind.DEPENDS_ON,
            RelationshipKind.CONSUMES, RelationshipKind.PRODUCES, RelationshipKind.READS_FROM,
            RelationshipKind.WRITES_TO, RelationshipKind.MAPS_TO, RelationshipKind.PERSISTS_TO,
            RelationshipKind.VALIDATED_BY, RelationshipKind.MANAGES, RelationshipKind.RENAMES,
            RelationshipKind.DECLARES_MAIN);

    private static final Set<RelationshipKind> CALL_KINDS =
            EnumSet.of(RelationshipKind.CALLS, RelationshipKind.INVOKES);

    private static final Set<RelationshipKind> DB_KINDS = EnumSet.of(
            RelationshipKind.MAPS_TO, RelationshipKind.PERSISTS_TO,
            RelationshipKind.READS_FROM, RelationshipKind.WRITES_TO, RelationshipKind.USES);

    private static final Pattern ENDPOINT_SHORTHAND =
            Pattern.compile("^(GET|POST|PUT|PATCH|DELETE)\\s+(/\\S*)$", Pattern.CASE_INSENSITIVE);

    public static final int DEFAULT_LIMIT = 50;

    private final AtlasStore store;
    private final SoftwareModel model;
    private final ScanRecord scan;
    private final List<Diagnostic> diagnostics;
    private AnalysisResult analysis; // lazy, deterministic, computed at most once

    private AtlasToolApi(AtlasStore store, SoftwareModel model, ScanRecord scan, List<Diagnostic> diagnostics) {
        this.store = store;
        this.model = model;
        this.scan = scan;
        this.diagnostics = diagnostics;
    }

    /**
     * Opens the index at {@code indexPath} read-only and serves the latest
     * completed scan. Fails when no completed scan exists — the API never
     * fabricates an empty repository view.
     */
    public static AtlasToolApi open(Path indexPath) {
        AtlasStore store = AtlasStore.atPathReadOnly(indexPath);
        try {
            ScanRecord scan = store.latestCompletedScan()
                    .orElseThrow(() -> new IllegalStateException(
                            "The index has no completed scan — run 'atlas scan' first"));
            return new AtlasToolApi(store, store.loadModel(), scan, store.loadDiagnostics());
        } catch (RuntimeException e) {
            store.close();
            throw e;
        }
    }

    public String scanId() {
        return scan.scanKey();
    }

    /** The index schema version behind this scan (for provenance reporting). */
    public String schemaVersion() {
        return AtlasStore.schemaVersion();
    }

    // ---- entity lookup ----

    /**
     * Resolves a stable id, an endpoint shorthand ({@code "POST /customers"}) or a
     * unique name suffix to one entity. Ambiguity returns an empty value with the
     * candidate ids in the note — never an arbitrary pick.
     */
    public ToolResult<Optional<Views.EntityView>> findEntity(String query) {
        String q = query.trim();
        Matcher endpoint = ENDPOINT_SHORTHAND.matcher(q);
        if (endpoint.matches()) {
            String id = "java:endpoint:" + endpoint.group(1).toUpperCase(Locale.ROOT) + ":" + endpoint.group(2);
            return getEntityInternal(id, "no endpoint '" + q + "' in this scan");
        }
        if (q.contains(":")) {
            return getEntityInternal(q, "no entity with stable id '" + q + "'");
        }
        List<Entity> matches = model.entities().stream()
                .filter(e -> e.qualifiedName().equals(q) || e.qualifiedName().endsWith("." + q)
                        || e.qualifiedName().endsWith("#" + q) || e.name().equals(q))
                .sorted(Comparator.comparing(Entity::id))
                .toList();
        if (matches.size() == 1) {
            return ToolResult.of(scanId(), Optional.of(Views.EntityView.of(matches.get(0))));
        }
        if (matches.isEmpty()) {
            return ToolResult.of(scanId(), Optional.empty(), false, 0, "no entity matches '" + q + "'");
        }
        String candidates = matches.stream().limit(10).map(Entity::id)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return ToolResult.of(scanId(), Optional.empty(), matches.size() > 10, matches.size(),
                "'" + q + "' is ambiguous; candidates: " + candidates);
    }

    public ToolResult<Optional<Views.EntityView>> getEntity(String stableId) {
        return getEntityInternal(stableId, "no entity with stable id '" + stableId + "'");
    }

    private ToolResult<Optional<Views.EntityView>> getEntityInternal(String id, String missingNote) {
        Optional<Entity> entity = model.entity(id);
        return entity.map(e -> ToolResult.of(scanId(), Optional.of(Views.EntityView.of(e))))
                .orElseGet(() -> ToolResult.of(scanId(), Optional.empty(), false, 0, missingNote));
    }

    /** Case-insensitive substring search over names and qualified names. */
    public ToolResult<List<Views.EntityView>> searchEntities(String text, String kind, String language,
                                                             int limit) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<Views.EntityView> all = model.entities().stream()
                .filter(e -> kind == null || e.kind().name().equalsIgnoreCase(kind))
                .filter(e -> language == null || e.language().equalsIgnoreCase(language))
                .filter(e -> e.qualifiedName().toLowerCase(Locale.ROOT).contains(needle)
                        || e.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Entity::id))
                .map(Views.EntityView::of)
                .toList();
        return limited(all, limit);
    }

    // ---- evidence ----

    public ToolResult<Optional<Views.EvidenceView>> getSourceEvidence(String stableId) {
        Entity e = model.entity(stableId).orElse(null);
        if (e == null) {
            return ToolResult.of(scanId(), Optional.empty(), false, 0,
                    "no entity with stable id '" + stableId + "'");
        }
        String location = e.location().map(Object::toString).orElse("");
        String filePath = e.location().map(l -> l.filePath()).orElse(null);
        String fileHash = filePath == null ? "" : model.entity("file:" + filePath)
                .flatMap(f -> f.attribute(Entity.Attributes.FILE_HASH)).orElse("");
        String note = "Source text is not persisted in the index; evidence is locations and hashes.";
        return ToolResult.of(scanId(), Optional.of(new Views.EvidenceView(stableId, location,
                        e.attribute(Entity.Attributes.SPEC_LOCATION).orElse(""),
                        e.attribute(Entity.Attributes.BODY_LOCATION).orElse(""),
                        fileHash, note)),
                false, -1, "");
    }

    // ---- graph neighborhood ----

    public ToolResult<List<Views.NeighborView>> getCallers(String stableId, int limit) {
        return neighbors(stableId, CALL_KINDS, true, limit);
    }

    public ToolResult<List<Views.NeighborView>> getCallees(String stableId, int limit) {
        return neighbors(stableId, CALL_KINDS, false, limit);
    }

    public ToolResult<List<Views.NeighborView>> getDependents(String stableId, int limit) {
        return neighbors(stableId, USAGE, true, limit);
    }

    public ToolResult<List<Views.NeighborView>> getDependencies(String stableId, int limit) {
        return neighbors(stableId, USAGE, false, limit);
    }

    /** Structural members of a container (package → types, type → methods/fields). */
    public ToolResult<List<Views.NeighborView>> getMembers(String stableId, int limit) {
        return neighbors(stableId, EnumSet.of(RelationshipKind.CONTAINS), false, limit);
    }

    private ToolResult<List<Views.NeighborView>> neighbors(String stableId, Set<RelationshipKind> kinds,
                                                           boolean incoming, int limit) {
        if (model.entity(stableId).isEmpty()) {
            return ToolResult.of(scanId(), List.of(), false, 0,
                    "no entity with stable id '" + stableId + "'");
        }
        List<Relationship> edges = incoming ? model.incoming(stableId) : model.outgoing(stableId);
        List<Relationship> filtered = edges.stream()
                .filter(r -> r.resolved() && kinds.contains(r.kind()))
                .toList();

        // The same call pair can appear both as an evidence-bearing lineage INVOKES
        // edge and as a bare name-resolution CALLS edge from the Linker. Agents get
        // one edge per pair: the one carrying rule evidence wins.
        Set<String> invokedPairs = new HashSet<>();
        for (Relationship r : filtered) {
            if (r.kind() == RelationshipKind.INVOKES) {
                invokedPairs.add(r.fromId() + "->" + r.toId());
            }
        }
        List<Views.NeighborView> all = filtered.stream()
                .filter(r -> r.kind() != RelationshipKind.CALLS
                        || !invokedPairs.contains(r.fromId() + "->" + r.toId()))
                .sorted(Comparator.comparing((Relationship r) -> r.kind().name())
                        .thenComparing(r -> incoming ? r.fromId() : r.toId()))
                .map(r -> {
                    String otherId = incoming ? r.fromId() : r.toId();
                    return model.entity(otherId)
                            .map(o -> new Views.NeighborView(Views.EntityView.of(o), Views.EdgeView.of(r)))
                            .orElse(null);
                })
                .filter(n -> n != null)
                .toList();
        return limited(all, limit);
    }

    // ---- data lineage ----

    public ToolResult<List<Views.EntityView>> getDataSources() {
        return ToolResult.of(scanId(), sortedViews(EntityKind.DATA_SOURCE));
    }

    public ToolResult<List<Views.EntityView>> getDataSinks() {
        return ToolResult.of(scanId(), sortedViews(EntityKind.DATA_SINK));
    }

    /** Edges connecting {@code stableId} (or any entity when null) to database tables. */
    public ToolResult<List<Views.NeighborView>> getDatabaseReferences(String stableId, int limit) {
        List<Views.NeighborView> all = model.relationships().stream()
                .filter(Relationship::resolved)
                .filter(r -> DB_KINDS.contains(r.kind()))
                .filter(r -> isTable(r.toId()) || isTable(r.fromId()))
                .filter(r -> stableId == null || r.fromId().equals(stableId) || r.toId().equals(stableId))
                .sorted(Comparator.comparing((Relationship r) -> r.kind().name())
                        .thenComparing(Relationship::fromId).thenComparing(Relationship::toId))
                .map(r -> {
                    String otherId = isTable(r.toId()) ? r.toId() : r.fromId();
                    return model.entity(otherId)
                            .map(o -> new Views.NeighborView(Views.EntityView.of(o), Views.EdgeView.of(r)))
                            .orElse(null);
                })
                .filter(n -> n != null)
                .toList();
        return limited(all, limit);
    }

    public ToolResult<LineageResult> traceDataLineage(LineageQuery query) {
        return ToolResult.of(scanId(), new LineageService().trace(model, query));
    }

    // ---- analysis views ----

    public ToolResult<List<DeadCodeCandidate>> findDeadCodeCandidates(int limit) {
        List<DeadCodeCandidate> all = analysis().deadCode();
        boolean truncated = all.size() > Math.max(1, limit);
        return ToolResult.of(scanId(), all.stream().limit(Math.max(1, limit)).toList(),
                truncated, all.size(),
                "Probable only — review before removal; confidence rules are documented in EVIDENCE_MODEL.md");
    }

    public ToolResult<List<ComplexityHotspot>> getComplexity(int limit) {
        List<ComplexityHotspot> all = analysis().complexityHotspots();
        boolean truncated = all.size() > Math.max(1, limit);
        return ToolResult.of(scanId(), all.stream().limit(Math.max(1, limit)).toList(), truncated, all.size());
    }

    public ToolResult<Views.RepositorySummaryView> getRepositorySummary() {
        AnalysisResult a = analysis();
        Map<String, Integer> entityCounts = new TreeMap<>();
        a.metrics().entityCounts().forEach((k, v) -> entityCounts.put(k.name(), v));
        List<String> endpoints = model.entitiesOfKind(EntityKind.ENDPOINT).stream()
                .map(Entity::name).sorted().toList();
        List<String> tables = model.entitiesOfKind(EntityKind.DATABASE_OBJECT).stream()
                .map(Entity::name).sorted().toList();
        List<String> sources = model.entitiesOfKind(EntityKind.DATA_SOURCE).stream()
                .map(Entity::name).sorted().toList();
        List<String> sinks = model.entitiesOfKind(EntityKind.DATA_SINK).stream()
                .map(Entity::name).sorted().toList();
        int unresolved = (int) model.relationships().stream().filter(r -> !r.resolved()).count();
        return ToolResult.of(scanId(), new Views.RepositorySummaryView(
                a.metrics().totalFiles(), a.metrics().totalLines(), a.metrics().codeLines(),
                new TreeMap<>(a.metrics().filesByLanguage()), entityCounts,
                endpoints, tables, sources, sinks,
                a.deadCode().size(), a.complexityHotspots().size(), unresolved, diagnostics.size()));
    }

    /**
     * References the Linker could <em>not</em> resolve.
     *
     * <p>The Linker preserves each original symbolic edge for auditability and adds a
     * resolved edge beside it, so an unresolved-looking edge may in fact have been
     * resolved. Those originals are excluded here: an "unresolved reference" must
     * mean the target was genuinely not found, or the count contradicts the scan's
     * own coverage numbers.
     */
    public ToolResult<List<Views.UnresolvedReference>> getUnresolvedReferences(int limit) {
        Set<String> resolvedTwins = resolvedTwinKeys();
        List<Views.UnresolvedReference> all = model.relationships().stream()
                .filter(r -> !r.resolved())
                .filter(r -> !resolvedTwins.contains(twinKey(r)))
                .sorted(Comparator.comparing(Relationship::fromId)
                        .thenComparing(Relationship::toId)
                        .thenComparing(r -> r.kind().name()))
                .map(r -> new Views.UnresolvedReference(r.fromId(),
                        r.attributes().getOrDefault(EvidenceKeys.CALL_NAME,
                                r.attributes().getOrDefault(EvidenceKeys.TYPE_NAME, r.toId())),
                        r.kind().name(),
                        r.location().map(Object::toString).orElse("")))
                .toList();
        return limited(all, limit);
    }

    public ToolResult<List<Diagnostic>> getDiagnostics() {
        return ToolResult.of(scanId(), diagnostics);
    }

    /**
     * Resolved / unresolved / ambiguous cross-reference counts derived from the
     * persisted model (structural {@code CONTAINS} edges excluded). Lets a reused
     * scan report an honest resolution rate without re-running the pipeline.
     */
    public ToolResult<Views.ReferenceCounts> getReferenceCounts() {
        Set<String> resolvedTwins = resolvedTwinKeys();
        int resolved = 0;
        int unresolved = 0;
        int ambiguous = 0;
        for (Relationship r : model.relationships()) {
            if (r.kind() == RelationshipKind.CONTAINS) {
                continue;
            }
            if (r.resolved()) {
                resolved++;
            } else if (resolvedTwins.contains(twinKey(r))) {
                // The Linker kept this original beside the resolved edge it produced;
                // counting it as unresolved would double-count a reference that
                // actually resolved.
                continue;
            } else if ("true".equals(r.attributes().get(EvidenceKeys.AMBIGUOUS))) {
                ambiguous++;
            } else {
                unresolved++;
            }
        }
        return ToolResult.of(scanId(), new Views.ReferenceCounts(resolved, unresolved, ambiguous));
    }

    /**
     * Keys of symbolic references the Linker successfully resolved. The Linker copies
     * an original's evidence (including its {@code callName}/{@code typeName}) onto
     * the resolved edge, so a resolved edge and the original it came from share a key.
     */
    private Set<String> resolvedTwinKeys() {
        Set<String> keys = new HashSet<>();
        for (Relationship r : model.relationships()) {
            if (r.resolved()) {
                String key = twinKey(r);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** Identity of a symbolic reference: its source, kind and the name it named. */
    private static String twinKey(Relationship r) {
        String name = r.attributes().get(EvidenceKeys.CALL_NAME);
        if (name == null) {
            name = r.attributes().get(EvidenceKeys.TYPE_NAME);
        }
        return name == null ? null : r.fromId() + "|" + r.kind() + "|" + name;
    }

    // ---- impact ----

    /**
     * Deterministic change-impact assessment: direct dependents, transitive
     * dependents (breadth-first over incoming usage edges), database impact via
     * shared tables, downstream lineage, and honest unresolved risk.
     */
    public ToolResult<Views.ImpactView> calculateChangeImpact(String stableId, int maxDepth, int limit) {
        Entity target = model.entity(stableId).orElse(null);
        if (target == null) {
            return ToolResult.of(scanId(), null, false, 0,
                    "no entity with stable id '" + stableId + "'");
        }

        List<Views.NeighborView> direct = neighbors(stableId, USAGE, true, limit).value();

        // Transitive dependents, excluding the target and direct layer.
        Set<String> seen = new HashSet<>();
        seen.add(stableId);
        direct.forEach(n -> seen.add(n.entity().stableId()));
        // Seeding both layers makes the first expansion discover only indirect impact.
        Deque<String> frontier = new ArrayDeque<>(seen);
        Set<String> indirectIds = new LinkedHashSet<>();
        int depth = 0;
        boolean truncated = false;
        while (!frontier.isEmpty() && depth < Math.max(1, maxDepth)) {
            Deque<String> next = new ArrayDeque<>();
            for (String id : frontier) {
                for (Relationship r : model.incoming(id)) {
                    if (!r.resolved() || !USAGE.contains(r.kind())) {
                        continue;
                    }
                    String from = r.fromId();
                    if (seen.add(from)) {
                        indirectIds.add(from);
                        next.add(from);
                    }
                }
            }
            frontier = next;
            depth++;
        }
        if (!frontier.isEmpty()) {
            truncated = true;
        }
        List<Views.EntityView> indirect = indirectIds.stream().sorted()
                .limit(Math.max(1, limit))
                .map(id -> model.entity(id).map(Views.EntityView::of).orElse(null))
                .filter(v -> v != null)
                .toList();
        if (indirectIds.size() > limit) {
            truncated = true;
        }

        // Database impact: tables the target touches, and who else touches them.
        List<Views.NeighborView> databaseImpact = new ArrayList<>();
        for (Relationship out : model.outgoing(stableId)) {
            if (!out.resolved() || !DB_KINDS.contains(out.kind()) || !isTable(out.toId())) {
                continue;
            }
            for (Relationship touch : model.incoming(out.toId())) {
                if (touch.resolved() && DB_KINDS.contains(touch.kind())
                        && !touch.fromId().equals(stableId)) {
                    model.entity(touch.fromId()).ifPresent(o ->
                            databaseImpact.add(new Views.NeighborView(Views.EntityView.of(o),
                                    Views.EdgeView.of(touch))));
                }
            }
        }
        databaseImpact.sort(Comparator.comparing(n -> n.entity().stableId()));

        // Downstream lineage: what this entity feeds.
        LineageResult downstream = new LineageService().trace(model, LineageQuery.downstream(stableId));
        List<String> lineageIds = downstream.nodes().stream()
                .map(LineageResult.Node::id)
                .filter(id -> !id.equals(stableId))
                .sorted().limit(Math.max(1, limit)).toList();

        // Unresolved risk: unresolved references sharing the target's simple name.
        String simple = target.name().toLowerCase(Locale.ROOT);
        List<String> risks = model.relationships().stream()
                .filter(r -> !r.resolved())
                .filter(r -> {
                    String name = r.attributes().getOrDefault(EvidenceKeys.CALL_NAME,
                            r.attributes().getOrDefault(EvidenceKeys.TYPE_NAME, r.toId()));
                    String s = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                    return s.toLowerCase(Locale.ROOT).equals(simple);
                })
                .map(r -> "possible reference from " + r.fromId()
                        + r.location().map(l -> " (" + l + ")").orElse(""))
                .sorted().distinct().limit(Math.max(1, limit)).toList();

        String limitations = "Reflection, dependency injection wiring and dynamic paths may add "
                + "impact Code Atlas cannot see. Build membership and configuration references are "
                + "available separately (get_build_membership, get_configuration_references) and are "
                + "not folded into this assessment.";
        return ToolResult.of(scanId(), new Views.ImpactView(stableId, direct, indirect,
                List.copyOf(databaseImpact), lineageIds, risks, limitations), truncated, -1, "");
    }

    /**
     * Which build module owns an entity — resolved through the file it lives in,
     * since build membership is a property of the file's location in the tree. With
     * a {@code null} id, returns every module→file membership edge.
     *
     * <p>An entity in a file that no module directory covers yields an empty (but
     * supported) answer: unowned, not unknown.
     */
    public ToolResult<List<Views.NeighborView>> getBuildMembership(String stableId) {
        if (model.entitiesOfKind(EntityKind.MODULE).isEmpty()) {
            return ToolResult.of(scanId(), List.of(), false, 0,
                    "No build files (Maven/Gradle/.gpr) were found in this repository, "
                            + "so nothing declares module membership");
        }
        if (stableId == null) {
            List<Views.NeighborView> all = moduleFileEdges()
                    .map(r -> model.entity(r.fromId())
                            .map(m -> new Views.NeighborView(Views.EntityView.of(m), Views.EdgeView.of(r)))
                            .orElse(null))
                    .filter(n -> n != null)
                    .toList();
            return limited(all, DEFAULT_LIMIT);
        }
        Entity entity = model.entity(stableId).orElse(null);
        if (entity == null) {
            return ToolResult.of(scanId(), List.of(), false, 0,
                    "no entity with stable id '" + stableId + "'");
        }
        // An entity belongs to the module that owns its file (a module owns itself).
        String fileId = entity.kind() == EntityKind.FILE ? entity.id()
                : entity.location().map(l -> "file:" + l.filePath()).orElse(null);
        if (fileId == null) {
            return ToolResult.of(scanId(), List.of(), false, 0,
                    "'" + stableId + "' has no source location, so its module cannot be determined");
        }
        List<Views.NeighborView> owners = model.incoming(fileId).stream()
                .filter(r -> r.kind() == RelationshipKind.CONTAINS)
                .filter(r -> model.entity(r.fromId()).map(e -> e.kind() == EntityKind.MODULE).orElse(false))
                .sorted(Comparator.comparing(Relationship::fromId))
                .map(r -> model.entity(r.fromId())
                        .map(m -> new Views.NeighborView(Views.EntityView.of(m), Views.EdgeView.of(r)))
                        .orElse(null))
                .filter(n -> n != null)
                .toList();
        return owners.isEmpty()
                ? ToolResult.of(scanId(), List.of(), false, 0,
                        "'" + stableId + "' is not inside any build module's directory")
                : ToolResult.of(scanId(), owners, false, owners.size());
    }

    private java.util.stream.Stream<Relationship> moduleFileEdges() {
        return model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.CONTAINS)
                .filter(r -> model.entity(r.fromId()).map(e -> e.kind() == EntityKind.MODULE).orElse(false))
                .filter(r -> model.entity(r.toId()).map(e -> e.kind() == EntityKind.FILE).orElse(false))
                .sorted(Comparator.comparing(Relationship::fromId).thenComparing(Relationship::toId));
    }

    /**
     * Configuration → code references: the {@code CONFIGURES} edges from parsed
     * configuration files. With a {@code stableId}, only references to (or from)
     * that entity; otherwise all of them. Each carries the config key and location.
     */
    public ToolResult<List<Views.NeighborView>> getConfigurationReferences(String stableId, int limit) {
        List<Views.NeighborView> all = model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.CONFIGURES && r.resolved())
                .filter(r -> stableId == null || r.fromId().equals(stableId) || r.toId().equals(stableId))
                .sorted(Comparator.comparing(Relationship::fromId).thenComparing(Relationship::toId))
                .map(r -> {
                    // Present the "other end": the referenced class, or (from a class's
                    // view) the configuration that references it.
                    String otherId = stableId != null && stableId.equals(r.toId()) ? r.fromId() : r.toId();
                    return model.entity(otherId)
                            .map(o -> new Views.NeighborView(Views.EntityView.of(o), Views.EdgeView.of(r)))
                            .orElse(null);
                })
                .filter(n -> n != null)
                .toList();
        return limited(all, limit);
    }

    // ---- helpers ----

    private AnalysisResult analysis() {
        if (analysis == null) {
            analysis = new AnalysisEngine().analyze(model);
        }
        return analysis;
    }

    private List<Views.EntityView> sortedViews(EntityKind kind) {
        return model.entitiesOfKind(kind).stream()
                .sorted(Comparator.comparing(Entity::id))
                .map(Views.EntityView::of)
                .toList();
    }

    private boolean isTable(String id) {
        return model.entity(id).map(e -> e.kind() == EntityKind.DATABASE_OBJECT).orElse(false);
    }

    private <T> ToolResult<List<T>> limited(List<T> all, int limit) {
        int max = limit <= 0 ? DEFAULT_LIMIT : limit;
        if (all.size() <= max) {
            return ToolResult.of(scanId(), all, false, all.size());
        }
        return ToolResult.of(scanId(), List.copyOf(all.subList(0, max)), true, all.size());
    }

    @Override
    public void close() {
        store.close();
    }
}
