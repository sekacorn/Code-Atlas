package com.codeatlas.index;

import com.codeatlas.model.Entity;
import com.codeatlas.model.Relationship;

import java.util.List;

/**
 * The complete cached record of one file's parse: its header plus the exact
 * entities and relationships the parser produced (pre-linking). Reusing an entry
 * on a later scan must be indistinguishable from re-parsing the file.
 */
public record CacheEntry(CachedFileMeta meta,
                         List<Entity> entities,
                         List<Relationship> relationships) {
}
