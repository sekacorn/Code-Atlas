package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assigns every scanned file to the build module that owns it.
 *
 * <p>Build parsers run per-file and cannot see the rest of the tree, so module
 * membership — a cross-file fact — is derived here: each file belongs to the
 * <em>deepest</em> module whose directory contains it. That nesting rule is what
 * makes a multi-module repository work: a file under {@code app/src} belongs to
 * {@code app}, not to the root aggregator that also contains {@code app}.
 *
 * <p>The result is a {@code CONTAINS} edge from module to file, which makes build
 * membership answerable for any entity via the file it lives in. Files outside every
 * module directory are simply left unassigned — never guessed.
 */
final class BuildMembershipLinker {

    /** Adds module→file CONTAINS edges; returns how many files were assigned. */
    int apply(SoftwareModel model) {
        List<Module> modules = new ArrayList<>();
        for (Entity m : model.entitiesOfKind(EntityKind.MODULE)) {
            m.attribute("moduleDir").ifPresent(dir -> modules.add(new Module(m.id(), normalize(dir))));
        }
        if (modules.isEmpty()) {
            return 0;
        }
        // Deepest directory first: the first match is the owning module.
        modules.sort(Comparator.comparingInt((Module m) -> m.dir.length()).reversed()
                .thenComparing(m -> m.id));

        List<Relationship> edges = new ArrayList<>();
        for (Entity file : model.entitiesOfKind(EntityKind.FILE)) {
            String path = normalize(file.qualifiedName());
            for (Module m : modules) {
                if (contains(m.dir, path)) {
                    edges.add(Relationship.builder(RelationshipKind.CONTAINS, m.id, file.id()).build());
                    break;
                }
            }
        }
        model.addRelationships(edges);
        return edges.size();
    }

    /** True when {@code dir} is the repository root ("") or an ancestor of {@code path}. */
    private static boolean contains(String dir, String path) {
        return dir.isEmpty() || path.startsWith(dir + "/");
    }

    private static String normalize(String p) {
        String s = p.replace('\\', '/');
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.equals(".") ? "" : s;
    }

    private record Module(String id, String dir) {
    }
}
