package com.codeatlas.analysis;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Identifies <em>probable</em> dead code and, crucially, quantifies how sure it is.
 *
 * <p>The detector is conservative by construction because call resolution is
 * imperfect on arbitrary offline repositories:
 * <ul>
 *   <li>only entities that are <b>not externally exposed</b> and have <b>no incoming
 *       usage edges</b> are candidates;</li>
 *   <li>if any <em>unresolved</em> call anywhere shares the candidate's simple name,
 *       confidence is cut sharply &mdash; it might be called through a path the
 *       parser could not resolve;</li>
 *   <li>confidence is capped below 100% and every finding recommends review.</li>
 * </ul>
 * This directly implements the spec's "never make absolute claims" requirement.
 */
public final class DeadCodeDetector {

    // Edge kinds that count as "someone uses this".
    private static final Set<RelationshipKind> USAGE = EnumSet.of(
            RelationshipKind.CALLS, RelationshipKind.REFERENCES, RelationshipKind.INHERITS,
            RelationshipKind.IMPLEMENTS, RelationshipKind.INSTANTIATES, RelationshipKind.USES,
            RelationshipKind.IMPORTS, RelationshipKind.RENAMES, RelationshipKind.DEPENDS_ON);

    // Entity kinds worth reporting as potentially dead. Packages are intentionally
    // excluded: nothing "uses" a package directly, so flagging them yields false
    // positives. Unused-package/namespace detection needs import-graph resolution and
    // is a planned enhancement.
    private static final Set<EntityKind> CANDIDATES = EnumSet.of(
            EntityKind.CLASS, EntityKind.ENUM, EntityKind.RECORD,
            EntityKind.METHOD, EntityKind.FUNCTION, EntityKind.PROCEDURE,
            EntityKind.FIELD, EntityKind.TYPE);

    private final int minConfidence;

    /** @param minConfidence findings below this confidence are suppressed */
    public DeadCodeDetector(int minConfidence) {
        this.minConfidence = minConfidence;
    }

    public DeadCodeDetector() {
        this(60);
    }

    public List<DeadCodeCandidate> detect(SoftwareModel model) {
        Set<String> unresolvedCallNames = collectUnresolvedTargetNames(model);
        Set<String> exposedQualifiedNames = collectExposedQualifiedNames(model);
        List<DeadCodeCandidate> findings = new ArrayList<>();

        for (Entity e : model.entities()) {
            if (!CANDIDATES.contains(e.kind())) {
                continue;
            }
            if (e.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false)) {
                continue;
            }
            // A declaration is exposed if any sibling with the same qualified name is
            // (e.g. an Ada body subprogram whose .ads spec is public).
            if (exposedQualifiedNames.contains(e.qualifiedName())) {
                continue;
            }
            long usageEdges = model.incoming(e.id()).stream()
                    .filter(r -> USAGE.contains(r.kind()) && r.resolved())
                    .count();
            if (usageEdges > 0) {
                continue;
            }

            List<String> evidence = new ArrayList<>();
            evidence.add("No incoming references found");
            evidence.add("Not externally exposed");

            int confidence = baseConfidence(e);

            // Ambiguity penalty: an unresolved call could actually reach this entity.
            boolean maybeReachable = unresolvedCallNames.contains(e.name().toLowerCase());
            if (maybeReachable) {
                confidence -= 30;
                evidence.add("Caution: an unresolved call shares this name");
            } else {
                evidence.add("No unresolved call matches its name");
            }

            String visibility = e.attribute(Entity.Attributes.VISIBILITY).orElse("");
            if (visibility.equals("private")) {
                evidence.add("Declared private");
            }

            confidence = Math.max(0, Math.min(96, confidence));
            if (confidence >= minConfidence) {
                findings.add(new DeadCodeCandidate(e.qualifiedName(), e.kind(), List.copyOf(evidence),
                        confidence, e.location().orElse(SourceLocation.ofFile("unknown"))));
            }
        }

        findings.sort(Comparator.comparingInt(DeadCodeCandidate::confidence).reversed()
                .thenComparing(DeadCodeCandidate::qualifiedName));
        return findings;
    }

    private int baseConfidence(Entity e) {
        String visibility = e.attribute(Entity.Attributes.VISIBILITY).orElse("");
        return switch (e.kind()) {
            case METHOD, FUNCTION, PROCEDURE -> switch (visibility) {
                case "private" -> 94;
                case "protected", "package" -> 86;
                default -> 82;
            };
            case FIELD -> 78; // field reads are under-tracked, so be humble
            case CLASS, ENUM, RECORD, TYPE -> 88;
            case PACKAGE -> 80;
            default -> 70;
        };
    }

    /** Qualified names that are externally exposed by at least one declaration. */
    private Set<String> collectExposedQualifiedNames(SoftwareModel model) {
        Set<String> exposed = new HashSet<>();
        for (Entity e : model.entities()) {
            if (e.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false)) {
                exposed.add(e.qualifiedName());
            }
        }
        return exposed;
    }

    /** Simple names targeted by unresolved edges &mdash; the "might still be used" set. */
    private Set<String> collectUnresolvedTargetNames(SoftwareModel model) {
        Set<String> names = new HashSet<>();
        for (Relationship r : model.relationships()) {
            if (!r.resolved() && USAGE.contains(r.kind())) {
                String name = r.attributes().getOrDefault("callName",
                        r.attributes().getOrDefault("typeName", r.toId()));
                int dot = name.lastIndexOf('.');
                names.add((dot >= 0 ? name.substring(dot + 1) : name).toLowerCase());
            }
        }
        return names;
    }
}
