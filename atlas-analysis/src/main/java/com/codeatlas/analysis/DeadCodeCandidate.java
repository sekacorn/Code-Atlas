package com.codeatlas.analysis;

import com.codeatlas.model.EntityKind;
import com.codeatlas.model.SourceLocation;

import java.util.List;

/**
 * A probable-dead-code finding. Deliberately never an absolute claim: it carries
 * the evidence that produced it and a confidence percentage, and always recommends
 * human review before removal.
 *
 * @param stableId      the entity's stable identifier (authoritative for suppressions/links)
 * @param qualifiedName the entity that looks unused
 * @param kind          what kind of artifact it is
 * @param evidence      human-readable checks that passed (e.g. "No references found")
 * @param confidence    0..100 confidence that this is genuinely dead
 * @param location      where it lives, for the reviewer
 */
public record DeadCodeCandidate(String stableId,
                                String qualifiedName,
                                EntityKind kind,
                                List<String> evidence,
                                int confidence,
                                SourceLocation location) {

    public DeadCodeCandidate {
        evidence = List.copyOf(evidence);
    }

    public String recommendation() {
        return "Review before removal";
    }
}
