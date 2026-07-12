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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic rule pass that turns parser-extracted lineage evidence into
 * explicit, evidence-backed lineage edges on the model.
 *
 * <p>Runs after the Linker (so type/impl edges are available) and before
 * persistence (so lineage facts are stored with the scan). Every edge carries a
 * rule id, rule version, analyzer id, fixed rule-derived confidence, resolution
 * status and — where available — the source location of the code that produced
 * it. References that cannot be resolved are emitted as explicit UNRESOLVED
 * edges rather than dropped, so gaps stay visible end-to-end.
 *
 * <p>The pass is conservative by design: it never picks one of several DI
 * implementations arbitrarily (all candidates are kept, marked ambiguous), never
 * fabricates a table name beyond the documented default-naming inference, and
 * never labels a method a transformation without type-flow or naming evidence.
 */
public final class LineageAnalyzer {

    private static final Set<String> WRITE_PREFIXES =
            Set.of("save", "insert", "update", "delete", "remove", "persist", "merge");
    private static final Set<String> READ_PREFIXES =
            Set.of("find", "get", "read", "query", "count", "exists", "search", "stream");
    private static final Set<String> MAPPER_NAME_PREFIXES = Set.of("to", "map", "convert", "from", "build");

    private static final Set<EntityKind> PROJECT_TYPES =
            Set.of(EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM, EntityKind.RECORD);

    private SoftwareModel model;
    private final Map<String, List<Entity>> typesBySimpleName = new HashMap<>();
    private final Map<String, Entity> typesByQn = new HashMap<>();
    private final Map<String, List<Entity>> methodsByOwnerQn = new HashMap<>();
    private final Map<String, Map<String, String>> fieldTypesByOwnerQn = new HashMap<>();
    private final Map<String, List<Entity>> implementationsOf = new HashMap<>();
    private final Set<String> emitted = new HashSet<>();
    private final List<Relationship> newEdges = new ArrayList<>();
    private final List<Entity> retagged = new ArrayList<>();

    /** Applies every lineage rule to the model, adding edges and role tags in place. */
    public void apply(SoftwareModel model) {
        this.model = model;
        buildIndexes();
        Map<String, TableRef> tablesByEntityQn = ruleJpaTables();
        Map<String, TableRef> tablesByRepositoryQn = ruleRepositories(tablesByEntityQn);
        ruleEndpointIo();
        ruleCallsAndPersistence(tablesByRepositoryQn);
        ruleTransformations();
        ruleValidation();
        model.addRelationships(newEdges);
        retagged.forEach(model::addEntity); // merge-in role tags
    }

    // ---- indexes ----

    private void buildIndexes() {
        for (Entity e : model.entities()) {
            if (PROJECT_TYPES.contains(e.kind())) {
                typesBySimpleName.computeIfAbsent(e.name(), k -> new ArrayList<>()).add(e);
                typesByQn.put(e.qualifiedName(), e);
            } else if (e.kind() == EntityKind.METHOD) {
                String qn = e.qualifiedName();
                int hash = qn.indexOf('#');
                if (hash > 0) {
                    methodsByOwnerQn.computeIfAbsent(qn.substring(0, hash), k -> new ArrayList<>()).add(e);
                }
            } else if (e.kind() == EntityKind.FIELD) {
                String qn = e.qualifiedName();
                int hash = qn.indexOf('#');
                String fieldType = e.attribute("fieldType").orElse(null);
                if (hash > 0 && fieldType != null) {
                    fieldTypesByOwnerQn.computeIfAbsent(qn.substring(0, hash), k -> new HashMap<>())
                            .put(e.name(), simpleName(fieldType));
                }
            }
        }
        for (Relationship r : model.relationships()) {
            if (r.kind() == RelationshipKind.IMPLEMENTS && r.resolved()) {
                model.entity(r.fromId()).ifPresent(impl ->
                        implementationsOf.computeIfAbsent(r.toId(), k -> new ArrayList<>()).add(impl));
            }
        }
        // Deterministic candidate ordering regardless of model iteration order.
        typesBySimpleName.values().forEach(l -> l.sort(java.util.Comparator.comparing(Entity::id)));
        methodsByOwnerQn.values().forEach(l -> l.sort(java.util.Comparator.comparing(Entity::id)));
        implementationsOf.values().forEach(l -> l.sort(java.util.Comparator.comparing(Entity::id)));
    }

    private record TableRef(Entity table, double confidence, boolean inferred) {
    }

    // ---- rules ----

    /** JPA @Entity types map to tables; explicit @Table is a fact, default naming is inferred. */
    private Map<String, TableRef> ruleJpaTables() {
        Map<String, TableRef> byEntityQn = new HashMap<>();
        for (Entity type : sortedTypes()) {
            if (!type.boolAttribute(Entity.Attributes.JPA_ENTITY, false)) {
                continue;
            }
            String explicit = type.attribute(Entity.Attributes.JPA_TABLE_NAME).orElse(null);
            boolean inferred = explicit == null;
            // Default physical naming is framework-configuration dependent; the
            // lower-cased simple name is a documented, conservative inference.
            String tableName = explicit != null ? explicit : type.name().toLowerCase(Locale.ROOT);
            Entity table = Entity.builder(EntityKind.DATABASE_OBJECT, tableName)
                    .qualifiedName(tableName)
                    .language("sql")
                    .attribute(Entity.Attributes.DB_OBJECT_TYPE, "table")
                    .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true)
                    .build();
            model.addEntity(table);
            double confidence = inferred ? 0.60 : 1.00;
            emit(RelationshipKind.MAPS_TO, type.id(), table.id(),
                    inferred ? LineageRules.JPA_TABLE_DEFAULT : LineageRules.JPA_TABLE_EXPLICIT,
                    confidence, inferred ? ResolutionStatus.INFERRED : ResolutionStatus.RESOLVED,
                    type.location().orElse(null), inferred);
            byEntityQn.put(type.qualifiedName(), new TableRef(table, confidence, inferred));
        }
        return byEntityQn;
    }

    /** Spring Data repositories manage their first type argument and persist to its table. */
    private Map<String, TableRef> ruleRepositories(Map<String, TableRef> tablesByEntityQn) {
        Map<String, TableRef> byRepositoryQn = new HashMap<>();
        for (Entity repo : sortedTypes()) {
            if (!repo.boolAttribute(Entity.Attributes.SPRING_DATA_REPOSITORY, false)) {
                continue;
            }
            String managedSimple = repo.attribute(Entity.Attributes.MANAGED_ENTITY_TYPE)
                    .map(LineageAnalyzer::simpleName).orElse(null);
            if (managedSimple == null) {
                continue;
            }
            Entity managed = uniqueTypeBySimpleName(managedSimple);
            if (managed == null) {
                continue; // entity type outside the analyzed repository: stays a visible gap
            }
            emit(RelationshipKind.MANAGES, repo.id(), managed.id(), LineageRules.REPOSITORY_MANAGES,
                    1.00, ResolutionStatus.RESOLVED, repo.location().orElse(null), false);
            TableRef table = tablesByEntityQn.get(managed.qualifiedName());
            if (table != null) {
                emit(RelationshipKind.PERSISTS_TO, repo.id(), table.table().id(),
                        LineageRules.REPOSITORY_TABLE, Math.min(0.95, table.confidence()),
                        table.inferred() ? ResolutionStatus.INFERRED : ResolutionStatus.RESOLVED,
                        repo.location().orElse(null), table.inferred());
                byRepositoryQn.put(repo.qualifiedName(), table);
            }
        }
        return byRepositoryQn;
    }

    /** Endpoints consume their request body type and produce their normalized return type. */
    private void ruleEndpointIo() {
        for (Entity endpoint : sorted(model.entitiesOfKind(EntityKind.ENDPOINT))) {
            endpoint.attribute(Entity.Attributes.REQUEST_BODY_TYPE).ifPresent(typeName -> {
                Entity dto = uniqueTypeBySimpleName(simpleName(typeName));
                if (dto != null) {
                    emit(RelationshipKind.CONSUMES, endpoint.id(), dto.id(), LineageRules.ENDPOINT_IO,
                            0.95, ResolutionStatus.RESOLVED, endpoint.location().orElse(null), false);
                    retag(dto, "dto-request");
                }
            });
            endpoint.attribute(Entity.Attributes.RETURN_TYPE_NORMALIZED).ifPresent(typeName -> {
                Entity dto = uniqueTypeBySimpleName(simpleName(typeName));
                if (dto != null) {
                    emit(RelationshipKind.PRODUCES, endpoint.id(), dto.id(), LineageRules.ENDPOINT_IO,
                            0.95, ResolutionStatus.RESOLVED, endpoint.location().orElse(null), false);
                    retag(dto, "dto-response");
                }
            });
        }
    }

    /**
     * Resolves parser CALLS edges into INVOKES / READS_FROM / WRITES_TO lineage
     * edges using declared receiver-field types, unique-implementation DI, and
     * same-class lookup. Unresolvable receivers become explicit UNRESOLVED edges.
     */
    private void ruleCallsAndPersistence(Map<String, TableRef> tablesByRepositoryQn) {
        List<Relationship> calls = model.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.CALLS && !r.resolved())
                .sorted(java.util.Comparator
                        .comparing(Relationship::fromId)
                        .thenComparing(r -> r.attributes().getOrDefault(EvidenceKeys.CALL_NAME, ""))
                        .thenComparing(r -> r.location().map(SourceLocation::toString).orElse("")))
                .toList();
        for (Relationship call : calls) {
            Entity caller = model.entity(call.fromId()).orElse(null);
            if (caller == null || caller.kind() == EntityKind.FILE) {
                continue;
            }
            String callName = call.attributes().getOrDefault(EvidenceKeys.CALL_NAME, call.toId());
            int argCount = parseIntOr(call.attributes().get(EvidenceKeys.ARG_COUNT), -1);
            String receiver = call.attributes().get(EvidenceKeys.RECEIVER_NAME);
            SourceLocation loc = call.location().orElse(null);
            String ownerQn = ownerOf(caller);

            if (receiver == null) {
                // Implicit this: same-class call.
                Entity target = methodIn(ownerQn, callName, argCount);
                if (target != null) {
                    emit(RelationshipKind.INVOKES, caller.id(), target.id(), LineageRules.CALL,
                            0.95, ResolutionStatus.RESOLVED, loc, false);
                }
                continue;
            }

            String receiverTypeSimple = fieldTypesByOwnerQn
                    .getOrDefault(ownerQn, Map.of()).get(receiver);
            boolean viaField = receiverTypeSimple != null;
            if (receiverTypeSimple == null && looksLikeTypeName(receiver)) {
                receiverTypeSimple = receiver; // static call: ClassName.method()
            }
            if (receiverTypeSimple == null) {
                continue; // local variable or chained receiver: out of scope, generic CALLS remains
            }

            Entity receiverType = uniqueTypeBySimpleName(receiverTypeSimple);
            if (receiverType == null) {
                if (viaField) {
                    // The receiver is a declared dependency whose type is outside the
                    // analyzed code: keep the reference as an explicit visible gap.
                    emit(RelationshipKind.INVOKES, caller.id(), receiverTypeSimple + "#" + callName,
                            LineageRules.UNRESOLVED, 0.40, ResolutionStatus.UNRESOLVED, loc, false);
                }
                continue;
            }

            // Repository receiver: classify the operation against its table.
            TableRef table = tablesByRepositoryQn.get(receiverType.qualifiedName());
            if (table != null) {
                RelationshipKind kind = classifyRepositoryOp(callName);
                String rule = kind == RelationshipKind.READS_FROM ? LineageRules.READ
                        : kind == RelationshipKind.WRITES_TO ? LineageRules.WRITE
                        : LineageRules.REPOSITORY_TOUCH;
                double confidence = (kind == RelationshipKind.USES ? 0.70 : 0.90) * table.confidence();
                emit(kind, caller.id(), table.table().id(), rule,
                        round2(confidence),
                        table.inferred() ? ResolutionStatus.INFERRED : ResolutionStatus.RESOLVED,
                        loc, table.inferred());
                // A declared custom finder on the repository interface is also invokable.
                Entity declared = methodIn(receiverType.qualifiedName(), callName, argCount);
                if (declared != null) {
                    emit(RelationshipKind.INVOKES, caller.id(), declared.id(), LineageRules.CALL,
                            0.95, ResolutionStatus.RESOLVED, loc, false);
                }
                continue;
            }

            if (receiverType.kind() == EntityKind.INTERFACE) {
                List<Entity> impls = implementationsOf.getOrDefault(receiverType.id(), List.of());
                if (impls.size() == 1) {
                    Entity target = methodIn(impls.get(0).qualifiedName(), callName, argCount);
                    if (target != null) {
                        emit(RelationshipKind.INVOKES, caller.id(), target.id(), LineageRules.DI_UNIQUE,
                                0.90, ResolutionStatus.RESOLVED, loc, false);
                        continue;
                    }
                } else if (impls.size() > 1) {
                    for (Entity impl : impls) {
                        Entity target = methodIn(impl.qualifiedName(), callName, argCount);
                        if (target != null) {
                            emitAmbiguous(caller.id(), target.id(), loc);
                        }
                    }
                    continue;
                }
                // Interface method declared in the interface itself.
                Entity declared = methodIn(receiverType.qualifiedName(), callName, argCount);
                if (declared != null) {
                    emit(RelationshipKind.INVOKES, caller.id(), declared.id(), LineageRules.CALL,
                            0.95, ResolutionStatus.RESOLVED, loc, false);
                }
                continue;
            }

            Entity target = methodIn(receiverType.qualifiedName(), callName, argCount);
            if (target != null) {
                emit(RelationshipKind.INVOKES, caller.id(), target.id(), LineageRules.CALL,
                        0.95, ResolutionStatus.RESOLVED, loc, false);
            }
        }
    }

    /** Type-flow (param + return + instantiation) or naming marks a method a transformation. */
    private void ruleTransformations() {
        for (Entity method : sortedMethods()) {
            String paramTypes = method.attribute(Entity.Attributes.PARAM_TYPES).orElse(null);
            String returnType = method.attribute(Entity.Attributes.RETURN_TYPE_NORMALIZED).orElse(null);
            if (paramTypes == null || returnType == null || paramTypes.contains(",")) {
                continue; // single-input transformations only, this milestone
            }
            Entity input = uniqueTypeBySimpleName(simpleName(stripGenerics(paramTypes)));
            Entity output = uniqueTypeBySimpleName(simpleName(returnType));
            if (input == null || output == null || input.id().equals(output.id())) {
                continue;
            }
            boolean instantiatesOutput = model.outgoing(method.id()).stream()
                    .anyMatch(r -> r.kind() == RelationshipKind.INSTANTIATES
                            && output.name().equals(r.attributes().getOrDefault(EvidenceKeys.TYPE_NAME,
                            r.resolved() ? "" : r.toId())));
            boolean namedLikeMapper = MAPPER_NAME_PREFIXES.stream()
                    .anyMatch(p -> method.name().toLowerCase(Locale.ROOT).startsWith(p));
            if (!instantiatesOutput && !namedLikeMapper) {
                continue;
            }
            String rule = instantiatesOutput ? LineageRules.MAP_TYPEFLOW : LineageRules.MAP_NAMING;
            double confidence = instantiatesOutput ? 0.90 : 0.60;
            ResolutionStatus status = instantiatesOutput ? ResolutionStatus.RESOLVED : ResolutionStatus.INFERRED;
            SourceLocation loc = method.location().orElse(null);
            emit(RelationshipKind.CONSUMES, method.id(), input.id(), rule, confidence, status, loc,
                    !instantiatesOutput);
            emit(RelationshipKind.PRODUCES, method.id(), output.id(), rule, confidence, status, loc,
                    !instantiatesOutput);
            retagAttribute(method, Entity.Attributes.TRANSFORMATION, "true");
        }
    }

    /** An INVOKES edge to a validate* method whose parameter type is known validates that type. */
    private void ruleValidation() {
        List<Relationship> invokes = newEdges.stream()
                .filter(r -> r.kind() == RelationshipKind.INVOKES && r.resolved())
                .sorted(java.util.Comparator.comparing(Relationship::toId).thenComparing(Relationship::fromId))
                .toList();
        for (Relationship edge : invokes) {
            Entity target = model.entity(edge.toId()).orElse(null);
            if (target == null || !target.name().toLowerCase(Locale.ROOT).startsWith("validate")) {
                continue;
            }
            String paramTypes = target.attribute(Entity.Attributes.PARAM_TYPES).orElse(null);
            if (paramTypes == null || paramTypes.contains(",")) {
                continue;
            }
            Entity validated = uniqueTypeBySimpleName(simpleName(stripGenerics(paramTypes)));
            if (validated != null) {
                emit(RelationshipKind.VALIDATED_BY, validated.id(), target.id(), LineageRules.VALIDATION,
                        0.90, ResolutionStatus.RESOLVED, edge.location().orElse(null), false);
            }
        }
    }

    // ---- helpers ----

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

    private void emitAmbiguous(String fromId, String toId, SourceLocation loc) {
        if (!emitted.add(RelationshipKind.INVOKES + "|" + fromId + "|" + toId)) {
            return;
        }
        newEdges.add(Relationship.builder(RelationshipKind.INVOKES, fromId, toId)
                .resolved(true)
                .status(ResolutionStatus.INFERRED)
                .attribute(EvidenceKeys.RULE_ID, LineageRules.DI_AMBIGUOUS)
                .attribute(EvidenceKeys.RULE_VERSION, LineageRules.RULE_VERSION)
                .attribute(EvidenceKeys.ANALYZER_ID, LineageRules.ANALYZER_ID)
                .attribute(EvidenceKeys.CONFIDENCE, "0.50")
                .attribute(EvidenceKeys.INFERRED, "true")
                .attribute(EvidenceKeys.AMBIGUOUS, "true")
                .location(loc != null ? loc : null)
                .build());
    }

    private void retag(Entity entity, String role) {
        retagAttribute(entity, Entity.Attributes.ROLE, role);
    }

    private void retagAttribute(Entity entity, String key, String value) {
        if (entity.attribute(key).isEmpty()) {
            retagged.add(entity.toBuilder().attribute(key, value).build());
        }
    }

    private RelationshipKind classifyRepositoryOp(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String p : WRITE_PREFIXES) {
            if (lower.startsWith(p)) {
                return RelationshipKind.WRITES_TO;
            }
        }
        for (String p : READ_PREFIXES) {
            if (lower.startsWith(p)) {
                return RelationshipKind.READS_FROM;
            }
        }
        // Unknown intent: a conservative "touches" edge, never a fabricated write.
        return RelationshipKind.USES;
    }

    private Entity uniqueTypeBySimpleName(String simpleName) {
        List<Entity> candidates = typesBySimpleName.getOrDefault(simpleName, List.of());
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private Entity methodIn(String ownerQn, String name, int argCount) {
        List<Entity> methods = methodsByOwnerQn.getOrDefault(ownerQn, List.of());
        Entity byArity = null;
        Entity byName = null;
        for (Entity m : methods) {
            if (!m.name().equals(name)) {
                continue;
            }
            if (byName == null) {
                byName = m;
            }
            if (argCount >= 0 && paramCount(m) == argCount) {
                if (byArity == null) {
                    byArity = m;
                }
            }
        }
        return byArity != null ? byArity : byName;
    }

    private static int paramCount(Entity method) {
        String params = method.attribute(Entity.Attributes.PARAM_TYPES).orElse("");
        if (params.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (char c : params.toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private List<Entity> sortedTypes() {
        return model.entities().stream()
                .filter(e -> PROJECT_TYPES.contains(e.kind()))
                .sorted(java.util.Comparator.comparing(Entity::id))
                .toList();
    }

    private List<Entity> sortedMethods() {
        return model.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD)
                .sorted(java.util.Comparator.comparing(Entity::id))
                .toList();
    }

    private static List<Entity> sorted(List<Entity> entities) {
        return entities.stream().sorted(java.util.Comparator.comparing(Entity::id)).toList();
    }

    private static String ownerOf(Entity callable) {
        String qn = callable.qualifiedName();
        int hash = qn.indexOf('#');
        return hash > 0 ? qn.substring(0, hash) : qn;
    }

    private static boolean looksLikeTypeName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private static String simpleName(String typeName) {
        String t = stripGenerics(typeName);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static String stripGenerics(String typeName) {
        int lt = typeName.indexOf('<');
        return (lt >= 0 ? typeName.substring(0, lt) : typeName).trim();
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
