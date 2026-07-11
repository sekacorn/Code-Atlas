package com.codeatlas.index;

import java.util.Set;

/**
 * The difference between a repository's current state and the previously indexed
 * state, computed from content hashes. This is the basis for incremental scans:
 * only {@link #added()} and {@link #changed()} files need re-parsing.
 */
public record ChangeSet(Set<String> added,
                        Set<String> changed,
                        Set<String> removed,
                        Set<String> unchanged) {

    public boolean isFirstRun() {
        return unchanged.isEmpty() && changed.isEmpty() && removed.isEmpty();
    }

    public int reparseCount() {
        return added.size() + changed.size();
    }
}
