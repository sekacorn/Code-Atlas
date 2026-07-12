package com.codeatlas.analysis.lineage;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic rule pass that turns Ada parser evidence into lineage edges,
 * covering the addendum's Ada flow: input source → procedure → transformation
 * function → package state → output or downstream consumer.
 *
 * <p>Same contract as the Java pass: runs after linking, before persistence;
 * every edge carries rule id/version, analyzer id, fixed confidence, resolution
 * status and source location; ambiguous overloads keep all candidates; calls into
 * withed-but-unanalyzed units become explicit UNRESOLVED edges (visible gaps);
 * unresolvable state candidates are dropped silently — assignment to non-state is
 * ordinary code, not a lineage gap.
 */
public final class AdaLineageAnalyzer {

    private static final Set<String> IO_INPUT_NAMES = Set.of("get", "get_line", "get_immediate");
    private static final Set<String> IO_OUTPUT_NAMES = Set.of("put", "put_line");
    private static final Set<String> MAPPER_NAME_PREFIXES =
            Set.of("to_", "convert", "from_", "make_", "build_", "transform");
    private static final String TEXT_IO_UNIT = "ada.text_io";

    private SoftwareModel model;
    private final Map<String, List<Entity>> callablesBySimpleName = new HashMap<>();
    private final Map<String, List<Entity>> callablesByQnPrefix = new HashMap<>();
    private final Map<String, Entity> variablesByQn = new HashMap<>();
    private final Map<String, List<Entity>> typesBySimpleName = new HashMap<>();
    private final Set<String> adaPackageQns = new HashSet<>();
    private final Map<String, Set<String>> withsByFile = new HashMap<>();
    private final Set<String> emitted = new HashSet<>();
    private final List<Relationship> newEdges = new ArrayList<>();
    private final List<Entity> retagged = new ArrayList<>();

    public void apply(SoftwareModel model) {
        this.model = model;
        buildIndexes();
        ruleState();
        ruleCallsAndIo();
        ruleTransformations();
        // Consume the parser's raw state candidates: each was either promoted to an
        // evidence-backed edge above, or refers to an ordinary non-state identifier
        // (an outer-block local, enum literal, package prefix) and is discarded —
        // that is normal code, not a lineage gap. Candidates must not linger, or
        // traversal would report them as false unresolved gaps.
        model.removeRelationships(r ->
                (r.kind() == RelationshipKind.WRITES_TO || r.kind() == RelationshipKind.READS_FROM)
                        && !r.resolved()
                        && r.attributes().containsKey(EvidenceKeys.STATE_NAME)
                        && !r.attributes().containsKey(EvidenceKeys.RULE_ID));
        model.addRelationships(newEdges);
        retagged.forEach(model::addEntity);
    }

    // ---- indexes ----

    private void buildIndexes() {
        for (Entity e : model.entities()) {
            if (!"ada".equals(e.language())) {
                continue;
            }
            switch (e.kind()) {
                case PROCEDURE, FUNCTION -> {
                    callablesBySimpleName.computeIfAbsent(e.name().toLowerCase(Locale.ROOT),
                            k -> new ArrayList<>()).add(e);
                    callablesByQnPrefix.computeIfAbsent(qnWithoutProfile(e.qualifiedName()),
                            k -> new ArrayList<>()).add(e);
                }
                case VARIABLE -> variablesByQn.put(e.qualifiedName().toLowerCase(Locale.ROOT), e);
                case TYPE -> typesBySimpleName.computeIfAbsent(e.name().toLowerCase(Locale.ROOT),
                        k -> new ArrayList<>()).add(e);
                case PACKAGE -> adaPackageQns.add(e.qualifiedName().toLowerCase(Locale.ROOT));
                default -> {
                }
            }
        }
        for (Relationship r : model.relationships()) {
            if (r.kind() == RelationshipKind.IMPORTS && r.fromId().startsWith("file:")) {
                String unit = r.attributes().get(EvidenceKeys.TYPE_NAME);
                if (unit != null) {
                    withsByFile.computeIfAbsent(r.fromId().substring("file:".length()),
                            k -> new HashSet<>()).add(unit.toLowerCase(Locale.ROOT));
                }
            }
        }
        callablesBySimpleName.values().forEach(l -> l.sort(Comparator.comparing(Entity::id)));
        callablesByQnPrefix.values().forEach(l -> l.sort(Comparator.comparing(Entity::id)));
        typesBySimpleName.values().forEach(l -> l.sort(Comparator.comparing(Entity::id)));
    }

    // ---- rules ----

    /** Resolves parser state-candidates into READS_FROM / WRITES_TO edges on variables. */
    private void ruleState() {
        for (Relationship candidate : sortedUnresolved(
                Set.of(RelationshipKind.WRITES_TO, RelationshipKind.READS_FROM))) {
            String stateName = candidate.attributes().get(EvidenceKeys.STATE_NAME);
            if (stateName == null || !isAdaCaller(candidate.fromId())) {
                continue;
            }
            String enclosing = candidate.attributes().getOrDefault(EvidenceKeys.ENCLOSING_PACKAGE, "");
            StateMatch match = resolveState(stateName, enclosing);
            if (match == null) {
                continue; // not package state: ordinary local/parameter code, no gap
            }
            boolean write = candidate.kind() == RelationshipKind.WRITES_TO;
            emit(candidate.kind(), candidate.fromId(), match.variable().id(),
                    write ? LineageRules.ADA_WRITE : LineageRules.ADA_READ,
                    match.qualified() ? 0.90 : 0.85, ResolutionStatus.RESOLVED,
                    candidate.location().orElse(null), false);
        }
    }

    private record StateMatch(Entity variable, boolean qualified) {
    }

    /**
     * Matches an assignment/reference target against known package state, trying
     * progressively shorter dotted prefixes so component references
     * ({@code Pkg.Var.Field}) still resolve to the variable.
     */
    private StateMatch resolveState(String target, String enclosingPackage) {
        String[] parts = target.split("\\.");
        for (int take = parts.length; take >= 1; take--) {
            String prefix = String.join(".", java.util.Arrays.copyOfRange(parts, 0, take));
            Entity byQn = variablesByQn.get(prefix.toLowerCase(Locale.ROOT));
            if (byQn != null) {
                return new StateMatch(byQn, take > 1);
            }
            if (!enclosingPackage.isEmpty()) {
                Entity local = variablesByQn.get((enclosingPackage + "." + prefix).toLowerCase(Locale.ROOT));
                if (local != null) {
                    return new StateMatch(local, false);
                }
            }
        }
        return null;
    }

    /** Resolves Ada calls into INVOKES edges, console I/O edges, or explicit external gaps. */
    private void ruleCallsAndIo() {
        for (Relationship call : sortedUnresolved(Set.of(RelationshipKind.CALLS))) {
            Entity caller = model.entity(call.fromId()).orElse(null);
            if (caller == null || !"ada".equals(caller.language())) {
                continue;
            }
            String simple = call.attributes().getOrDefault(EvidenceKeys.CALL_NAME, call.toId())
                    .toLowerCase(Locale.ROOT);
            String qualified = call.attributes().get(EvidenceKeys.QUALIFIED_CALL_NAME);
            SourceLocation loc = call.location().orElse(null);
            // With-clauses are file-scoped: use the CALL SITE's file, not the caller
            // entity's canonical location (a merged spec/body entity points at the spec).
            Set<String> withs = loc != null
                    ? withsByFile.getOrDefault(loc.filePath(), Set.of())
                    : Set.of();

            if (qualified != null) {
                List<Entity> byQn = callablesByQnPrefix.getOrDefault(
                        qualified.toLowerCase(Locale.ROOT), List.of());
                if (byQn.size() == 1) {
                    emit(RelationshipKind.INVOKES, caller.id(), byQn.get(0).id(), LineageRules.ADA_CALL,
                            0.95, ResolutionStatus.RESOLVED, loc, false);
                    continue;
                }
                if (byQn.size() > 1) {
                    for (Entity target : byQn) {
                        emitAmbiguous(caller.id(), target.id(), LineageRules.ADA_CALL_AMBIGUOUS, loc);
                    }
                    continue;
                }
                if (isTextIo(qualified, withs) && emitConsoleIo(caller, simple, loc)) {
                    continue;
                }
                String withedPrefix = withedPrefixOf(qualified, withs);
                if (withedPrefix != null && !adaPackageQns.contains(withedPrefix)) {
                    // A dependency the repository does not contain: keep it visible.
                    emit(RelationshipKind.INVOKES, caller.id(), qualified, LineageRules.ADA_EXTERNAL,
                            0.40, ResolutionStatus.UNRESOLVED, loc, false);
                }
                continue;
            }

            List<Entity> bySimple = callablesBySimpleName.getOrDefault(simple, List.of());
            if (bySimple.size() == 1) {
                Entity target = bySimple.get(0);
                if (!target.id().equals(caller.id())) {
                    emit(RelationshipKind.INVOKES, caller.id(), target.id(), LineageRules.ADA_CALL,
                            0.85, ResolutionStatus.RESOLVED, loc, false);
                }
                continue;
            }
            if (bySimple.size() > 1) {
                for (Entity target : bySimple) {
                    emitAmbiguous(caller.id(), target.id(), LineageRules.ADA_CALL_AMBIGUOUS, loc);
                }
                continue;
            }
            if (withs.contains(TEXT_IO_UNIT)) {
                emitConsoleIo(caller, simple, loc);
            }
        }
    }

    /** Console source/sink edges for Ada.Text_IO reads and writes. */
    private boolean emitConsoleIo(Entity caller, String simpleName, SourceLocation loc) {
        if (IO_INPUT_NAMES.contains(simpleName)) {
            Entity source = consoleEntity(EntityKind.DATA_SOURCE, "console_input",
                    "Ada.Text_IO standard input");
            emit(RelationshipKind.READS_FROM, caller.id(), source.id(), LineageRules.ADA_IO_READ,
                    0.85, ResolutionStatus.RESOLVED, loc, false);
            return true;
        }
        if (IO_OUTPUT_NAMES.contains(simpleName)) {
            Entity sink = consoleEntity(EntityKind.DATA_SINK, "console_output",
                    "Ada.Text_IO standard output");
            emit(RelationshipKind.WRITES_TO, caller.id(), sink.id(), LineageRules.ADA_IO_WRITE,
                    0.85, ResolutionStatus.RESOLVED, loc, false);
            return true;
        }
        return false;
    }

    private Entity consoleEntity(EntityKind kind, String name, String description) {
        Entity e = Entity.builder(kind, name)
                .qualifiedName(name)
                .language("ada")
                .attribute("description", description)
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true)
                .build();
        model.addEntity(e); // merge-safe: same stable id on every emission
        return e;
    }

    /** Single-parameter Ada functions between two project types are transformations. */
    private void ruleTransformations() {
        List<Entity> functions = model.entities().stream()
                .filter(e -> "ada".equals(e.language()) && e.kind() == EntityKind.FUNCTION)
                .sorted(Comparator.comparing(Entity::id))
                .toList();
        for (Entity fn : functions) {
            String paramTypes = fn.attribute(Entity.Attributes.PARAM_TYPES).orElse(null);
            String returnType = fn.attribute(Entity.Attributes.RETURN_TYPE).orElse(null);
            if (paramTypes == null || returnType == null || paramTypes.contains(",")) {
                continue;
            }
            Entity input = uniqueType(simpleName(paramTypes));
            Entity output = uniqueType(simpleName(returnType));
            if (input == null || output == null || input.id().equals(output.id())) {
                continue;
            }
            boolean namedLikeMapper = MAPPER_NAME_PREFIXES.stream()
                    .anyMatch(p -> fn.name().toLowerCase(Locale.ROOT).startsWith(p));
            String rule = namedLikeMapper ? LineageRules.ADA_MAP : LineageRules.ADA_MAP_TYPEFLOW;
            double confidence = namedLikeMapper ? 0.85 : 0.60;
            ResolutionStatus status = namedLikeMapper ? ResolutionStatus.RESOLVED : ResolutionStatus.INFERRED;
            SourceLocation loc = fn.location().orElse(null);
            emit(RelationshipKind.CONSUMES, fn.id(), input.id(), rule, confidence, status, loc, !namedLikeMapper);
            emit(RelationshipKind.PRODUCES, fn.id(), output.id(), rule, confidence, status, loc, !namedLikeMapper);
            if (fn.attribute(Entity.Attributes.TRANSFORMATION).isEmpty()) {
                retagged.add(fn.toBuilder().attribute(Entity.Attributes.TRANSFORMATION, "true").build());
            }
        }
    }

    // ---- helpers ----

    private List<Relationship> sortedUnresolved(Set<RelationshipKind> kinds) {
        return model.relationships().stream()
                .filter(r -> kinds.contains(r.kind()) && !r.resolved()
                        && !r.attributes().containsKey(EvidenceKeys.RULE_ID))
                .sorted(Comparator
                        .comparing(Relationship::fromId)
                        .thenComparing(Relationship::toId)
                        .thenComparing(r -> r.location().map(SourceLocation::toString).orElse("")))
                .toList();
    }

    private boolean isAdaCaller(String entityId) {
        return model.entity(entityId).map(e -> "ada".equals(e.language())).orElse(false);
    }

    private static boolean isTextIo(String qualified, Set<String> withs) {
        return withs.contains(TEXT_IO_UNIT) && qualified.toLowerCase(Locale.ROOT).startsWith(TEXT_IO_UNIT + ".");
    }

    /** The longest withed unit that prefixes the qualified call, or null. */
    private static String withedPrefixOf(String qualified, Set<String> withs) {
        String lower = qualified.toLowerCase(Locale.ROOT);
        String best = null;
        for (String w : withs) {
            if (lower.startsWith(w + ".") && (best == null || w.length() > best.length())) {
                best = w;
            }
        }
        return best;
    }

    private Entity uniqueType(String simpleName) {
        List<Entity> candidates = typesBySimpleName.getOrDefault(simpleName.toLowerCase(Locale.ROOT), List.of());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String qnWithoutProfile(String qualifiedName) {
        int paren = qualifiedName.indexOf('(');
        return (paren >= 0 ? qualifiedName.substring(0, paren) : qualifiedName).toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    private void emit(RelationshipKind kind, String fromId, String toId, String rule,
                      double confidence, ResolutionStatus status, SourceLocation location, boolean inferred) {
        if (!emitted.add(kind + "|" + fromId + "|" + toId)) {
            return;
        }
        Relationship.Builder b = Relationship.builder(kind, fromId, toId)
                .resolved(status == ResolutionStatus.RESOLVED || status == ResolutionStatus.DISCOVERED)
                .status(status)
                .attribute(EvidenceKeys.RULE_ID, rule)
                .attribute(EvidenceKeys.RULE_VERSION, LineageRules.RULE_VERSION)
                .attribute(EvidenceKeys.ANALYZER_ID, LineageRules.ANALYZER_ID)
                .attribute(EvidenceKeys.CONFIDENCE, String.format(Locale.ROOT, "%.2f", confidence));
        if (inferred) {
            b.attribute(EvidenceKeys.INFERRED, "true");
        }
        if (location != null) {
            b.location(location);
        }
        newEdges.add(b.build());
    }

    private void emitAmbiguous(String fromId, String toId, String rule, SourceLocation loc) {
        if (!emitted.add(RelationshipKind.INVOKES + "|" + fromId + "|" + toId)) {
            return;
        }
        Relationship.Builder b = Relationship.builder(RelationshipKind.INVOKES, fromId, toId)
                .resolved(true)
                .status(ResolutionStatus.INFERRED)
                .attribute(EvidenceKeys.RULE_ID, rule)
                .attribute(EvidenceKeys.RULE_VERSION, LineageRules.RULE_VERSION)
                .attribute(EvidenceKeys.ANALYZER_ID, LineageRules.ANALYZER_ID)
                .attribute(EvidenceKeys.CONFIDENCE, "0.50")
                .attribute(EvidenceKeys.INFERRED, "true")
                .attribute(EvidenceKeys.AMBIGUOUS, "true");
        if (loc != null) {
            b.location(loc);
        }
        newEdges.add(b.build());
    }
}
