package com.codeatlas.index;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Local, offline store for the software model, backed by embedded H2.
 *
 * <p>Schema v2 persists, per repository index:
 * <ul>
 *   <li><b>scan history</b> — one row per run with a deterministic content-derived
 *       scan key and a status; only a {@code COMPLETED} scan ever becomes current;</li>
 *   <li><b>the model snapshot</b> of the latest completed scan — entities with
 *       attributes, and relationships with resolution status, source location and
 *       attributes (rule ids, confidence, …);</li>
 *   <li><b>file hashes</b> for incremental change detection;</li>
 *   <li><b>a parse cache</b> — the exact per-file parser output, keyed by content
 *       hash and parser version, so unchanged files can be reused without
 *       re-parsing.</li>
 * </ul>
 *
 * <p>{@link #persistCompletedScan} replaces the snapshot in a single transaction:
 * a failure anywhere rolls back and the previous completed scan remains intact and
 * queryable. The store never writes inside the analyzed repository; callers choose
 * the database path (typically under the user's home directory).
 *
 * <p>Schema changes use a clean-reindex strategy: an index created by a different
 * schema version is dropped and rebuilt on open (the index is a cache, not a
 * system of record).
 */
public final class AtlasStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AtlasStore.class);
    private static final String SCHEMA_VERSION = "3";

    private final Connection connection;
    private final String url;
    private final boolean readOnly;

    private AtlasStore(String url, boolean readOnly) {
        this.url = url;
        this.readOnly = readOnly;
        try {
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(false);
            if (readOnly) {
                verifySchema();
            } else {
                initSchema();
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot open index at " + url, e);
        }
    }

    /** Opens a transient in-memory index (unit tests and explicit temporary sessions). */
    public static AtlasStore inMemory() {
        return new AtlasStore("jdbc:h2:mem:atlas-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", false);
    }

    /** Opens (or creates) a file-backed index at {@code dbPath} — no server, no admin. */
    public static AtlasStore atPath(Path dbPath) {
        return new AtlasStore("jdbc:h2:file:" + dbPath.toAbsolutePath() + ";AUTO_SERVER=FALSE", false);
    }

    /**
     * Opens an existing index <em>read-only</em>: the database engine itself
     * rejects every write ({@code ACCESS_MODE_DATA=r}), which is the storage-level
     * guarantee behind the agent tool boundary. The index must already exist with
     * the current schema version — a read-only handle never migrates or reindexes.
     */
    public static AtlasStore atPathReadOnly(Path dbPath) {
        return new AtlasStore("jdbc:h2:file:" + dbPath.toAbsolutePath()
                + ";AUTO_SERVER=FALSE;ACCESS_MODE_DATA=r", true);
    }

    /** Whether this handle was opened read-only (agent tool boundary). */
    public boolean isReadOnly() {
        return readOnly;
    }

    // ---- schema ----

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR PRIMARY KEY, v VARCHAR)");
        }
        connection.commit();

        String version = metaValue("schema_version").orElse(null);
        boolean hasLegacyTables = tableExists("ENTITY") && version == null;
        if (hasLegacyTables || (version != null && !SCHEMA_VERSION.equals(version))) {
            log.info("Index schema version {} found, expected {} — performing clean reindex",
                    version == null ? "pre-2" : version, SCHEMA_VERSION);
            dropAllTables();
        }
        createTables();
        setMeta("schema_version", SCHEMA_VERSION);
        connection.commit();
    }

    /** Read-only handles verify the schema instead of creating or migrating it. */
    private void verifySchema() throws SQLException {
        if (!tableExists("META")) {
            throw new IndexException("Index has no schema — run 'atlas scan' first", null);
        }
        String version = metaValue("schema_version").orElse(null);
        if (!SCHEMA_VERSION.equals(version)) {
            throw new IndexException("Index schema version " + version + " does not match "
                    + SCHEMA_VERSION + " — rescan the repository to reindex", null);
        }
    }

    private boolean tableExists(String upperName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")) {
            ps.setString(1, upperName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void dropAllTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String t : List.of("relationship_attr", "relationship", "entity_attr", "entity",
                    "file_hash", "cache_rel_attr", "cache_rel", "cache_entity_attr", "cache_entity",
                    "cache_file", "diagnostic", "scan")) {
                st.execute("DROP TABLE IF EXISTS " + t);
            }
        }
        connection.commit();
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS scan (
                        id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        scan_key   VARCHAR NOT NULL,
                        status     VARCHAR NOT NULL,
                        root       VARCHAR,
                        file_count INT,
                        started    TIMESTAMP,
                        completed  TIMESTAMP
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS entity (
                        id             VARCHAR PRIMARY KEY,
                        kind           VARCHAR NOT NULL,
                        name           VARCHAR NOT NULL,
                        qualified_name VARCHAR NOT NULL,
                        language       VARCHAR NOT NULL,
                        file_path      VARCHAR,
                        start_line     INT,
                        end_line       INT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS entity_attr (
                        entity_id VARCHAR NOT NULL,
                        attr_key  VARCHAR NOT NULL,
                        attr_val  VARCHAR,
                        PRIMARY KEY (entity_id, attr_key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS relationship (
                        id       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        from_id  VARCHAR NOT NULL,
                        to_id    VARCHAR NOT NULL,
                        kind     VARCHAR NOT NULL,
                        resolved BOOLEAN NOT NULL,
                        status   VARCHAR NOT NULL,
                        loc_file VARCHAR,
                        s_line   INT,
                        e_line   INT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS relationship_attr (
                        rel_id   BIGINT NOT NULL,
                        attr_key VARCHAR NOT NULL,
                        attr_val VARCHAR,
                        PRIMARY KEY (rel_id, attr_key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS file_hash (
                        path VARCHAR PRIMARY KEY,
                        hash VARCHAR NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cache_file (
                        path           VARCHAR PRIMARY KEY,
                        content_hash   VARCHAR NOT NULL,
                        parser_id      VARCHAR,
                        parser_version VARCHAR,
                        parse_status   VARCHAR NOT NULL,
                        total_lines    INT,
                        comment_lines  INT,
                        blank_lines    INT
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cache_entity (
                        path           VARCHAR NOT NULL,
                        ord            INT NOT NULL,
                        id             VARCHAR NOT NULL,
                        kind           VARCHAR NOT NULL,
                        name           VARCHAR NOT NULL,
                        qualified_name VARCHAR NOT NULL,
                        language       VARCHAR NOT NULL,
                        loc_file       VARCHAR,
                        s_line         INT,
                        e_line         INT,
                        s_col          INT,
                        e_col          INT,
                        PRIMARY KEY (path, ord)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cache_entity_attr (
                        path     VARCHAR NOT NULL,
                        ord      INT NOT NULL,
                        attr_key VARCHAR NOT NULL,
                        attr_val VARCHAR,
                        PRIMARY KEY (path, ord, attr_key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cache_rel (
                        path     VARCHAR NOT NULL,
                        ord      INT NOT NULL,
                        from_id  VARCHAR NOT NULL,
                        to_id    VARCHAR NOT NULL,
                        kind     VARCHAR NOT NULL,
                        resolved BOOLEAN NOT NULL,
                        status   VARCHAR NOT NULL,
                        loc_file VARCHAR,
                        s_line   INT,
                        e_line   INT,
                        PRIMARY KEY (path, ord)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS cache_rel_attr (
                        path     VARCHAR NOT NULL,
                        ord      INT NOT NULL,
                        attr_key VARCHAR NOT NULL,
                        attr_val VARCHAR,
                        PRIMARY KEY (path, ord, attr_key)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS diagnostic (
                        ord     INT NOT NULL,
                        code    VARCHAR NOT NULL,
                        message VARCHAR,
                        PRIMARY KEY (ord)
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rel_from ON relationship(from_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rel_to ON relationship(to_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entity_kind ON entity(kind)");
        }
    }

    private Optional<String> metaValue(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO meta (k, v) KEY (k) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    // ---- scan lifecycle ----

    /** Records the start of a scan run; committed immediately so a crash leaves an honest trace. */
    public long beginScan(String scanKey, String root, int fileCount) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO scan (scan_key, status, root, file_count, started) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scanKey);
            ps.setString(2, ScanRecord.IN_PROGRESS);
            ps.setString(3, root);
            ps.setInt(4, fileCount);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                long id = keys.getLong(1);
                connection.commit();
                return id;
            }
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IndexException("Cannot record scan start", e);
        }
    }

    /** Marks a scan failed. The previous completed snapshot remains untouched. */
    public void markScanFailed(long scanId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE scan SET status = ?, completed = ? WHERE id = ?")) {
            ps.setString(1, ScanRecord.FAILED);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setLong(3, scanId);
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IndexException("Cannot mark scan failed", e);
        }
    }

    /** The most recent successfully completed scan, if any. */
    public Optional<ScanRecord> latestCompletedScan() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, scan_key, status, file_count FROM scan WHERE status = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, ScanRecord.COMPLETED);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ScanRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot read scan history", e);
        }
    }

    /** All recorded scan runs, newest first (history stays distinguishable). */
    public List<ScanRecord> scanHistory() {
        List<ScanRecord> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, scan_key, status, file_count FROM scan ORDER BY id DESC")) {
            while (rs.next()) {
                out.add(new ScanRecord(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot read scan history", e);
        }
        return out;
    }

    /**
     * Atomically replaces the model snapshot, file hashes and parse-cache updates,
     * and marks the scan completed — all in one transaction. On any failure the
     * transaction rolls back: the previous completed scan stays current and intact.
     *
     * @param scanId     the row returned by {@link #beginScan}
     * @param model      the fully built model of this scan
     * @param pathToHash current file hashes
     * @param newCache   parse results produced fresh in this scan, to upsert into the cache
     * @param livePaths  all paths present in this scan; cache rows for other paths are pruned
     */
    public void persistCompletedScan(long scanId, SoftwareModel model, Map<String, String> pathToHash,
                                     Collection<CacheEntry> newCache, Set<String> livePaths) {
        try {
            clearModelTables();
            insertModel(model);
            replaceHashes(pathToHash);
            for (CacheEntry entry : newCache) {
                upsertCacheEntry(entry);
            }
            pruneCache(livePaths);
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE scan SET status = ?, completed = ? WHERE id = ?")) {
                ps.setString(1, ScanRecord.COMPLETED);
                ps.setTimestamp(2, Timestamp.from(Instant.now()));
                ps.setLong(3, scanId);
                ps.executeUpdate();
            }
            connection.commit();
            log.info("Persisted scan {}: {} entities, {} relationships, {} cache updates",
                    scanId, model.entityCount(), model.relationshipCount(), newCache.size());
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IndexException("Cannot persist scan " + scanId + " (previous scan preserved)", e);
        }
    }

    // ---- incremental change detection ----

    /** Content hashes recorded from the previous run: path -> hash. */
    public Map<String, String> storedHashes() {
        Map<String, String> hashes = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT path, hash FROM file_hash")) {
            while (rs.next()) {
                hashes.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot read file hashes", e);
        }
        return hashes;
    }

    /** Compares current path->hash against what is stored, without mutating anything. */
    public ChangeSet computeChanges(Map<String, String> current) {
        Map<String, String> previous = storedHashes();
        Set<String> added = new HashSet<>();
        Set<String> changed = new HashSet<>();
        Set<String> unchanged = new HashSet<>();
        for (var e : current.entrySet()) {
            String prior = previous.get(e.getKey());
            if (prior == null) {
                added.add(e.getKey());
            } else if (prior.equals(e.getValue())) {
                unchanged.add(e.getKey());
            } else {
                changed.add(e.getKey());
            }
        }
        Set<String> removed = new HashSet<>(previous.keySet());
        removed.removeAll(current.keySet());
        return new ChangeSet(added, changed, removed, unchanged);
    }

    // ---- parse cache ----

    /** Cache headers for every cached file: path -> meta. One query, safe to call once per scan. */
    public Map<String, CachedFileMeta> cacheIndex() {
        Map<String, CachedFileMeta> out = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT path, content_hash, parser_id, parser_version, parse_status, "
                             + "total_lines, comment_lines, blank_lines FROM cache_file")) {
            while (rs.next()) {
                out.put(rs.getString(1), new CachedFileMeta(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8)));
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot read parse cache index", e);
        }
        return out;
    }

    /** Loads the exact parser facts cached for one file (entities and relationships, in order). */
    public CacheEntry loadCachedFacts(CachedFileMeta meta) {
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();
        try {
            Map<Integer, Entity.Builder> byOrd = new HashMap<>();
            List<Integer> order = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ord, id, kind, name, qualified_name, language, loc_file, s_line, e_line, s_col, e_col "
                            + "FROM cache_entity WHERE path = ? ORDER BY ord")) {
                ps.setString(1, meta.path());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Entity.Builder b = Entity.builder(EntityKind.valueOf(rs.getString(3)), rs.getString(4))
                                .id(rs.getString(2))
                                .qualifiedName(rs.getString(5))
                                .language(rs.getString(6));
                        String locFile = rs.getString(7);
                        if (locFile != null) {
                            b.location(new SourceLocation(locFile, rs.getInt(8), rs.getInt(9),
                                    rs.getInt(10), rs.getInt(11)));
                        }
                        int ord = rs.getInt(1);
                        byOrd.put(ord, b);
                        order.add(ord);
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ord, attr_key, attr_val FROM cache_entity_attr WHERE path = ?")) {
                ps.setString(1, meta.path());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Entity.Builder b = byOrd.get(rs.getInt(1));
                        if (b != null) {
                            b.attribute(rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
            for (int ord : order) {
                entities.add(byOrd.get(ord).build());
            }

            Map<Integer, Relationship.Builder> relByOrd = new HashMap<>();
            List<Integer> relOrder = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ord, from_id, to_id, kind, resolved, status, loc_file, s_line, e_line "
                            + "FROM cache_rel WHERE path = ? ORDER BY ord")) {
                ps.setString(1, meta.path());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Relationship.Builder b = Relationship.builder(RelationshipKind.valueOf(rs.getString(4)),
                                        rs.getString(2), rs.getString(3))
                                .resolved(rs.getBoolean(5))
                                .status(ResolutionStatus.valueOf(rs.getString(6)));
                        String locFile = rs.getString(7);
                        if (locFile != null) {
                            b.location(new SourceLocation(locFile, rs.getInt(8), rs.getInt(9), 0, 0));
                        }
                        int ord = rs.getInt(1);
                        relByOrd.put(ord, b);
                        relOrder.add(ord);
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT ord, attr_key, attr_val FROM cache_rel_attr WHERE path = ?")) {
                ps.setString(1, meta.path());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Relationship.Builder b = relByOrd.get(rs.getInt(1));
                        if (b != null) {
                            b.attribute(rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
            for (int ord : relOrder) {
                relationships.add(relByOrd.get(ord).build());
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot load cached facts for " + meta.path(), e);
        }
        return new CacheEntry(meta, entities, relationships);
    }

    private void upsertCacheEntry(CacheEntry entry) throws SQLException {
        CachedFileMeta meta = entry.meta();
        try (PreparedStatement del = connection.prepareStatement("DELETE FROM cache_file WHERE path = ?")) {
            del.setString(1, meta.path());
            del.executeUpdate();
        }
        for (String table : List.of("cache_entity", "cache_entity_attr", "cache_rel", "cache_rel_attr")) {
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM " + table + " WHERE path = ?")) {
                del.setString(1, meta.path());
                del.executeUpdate();
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO cache_file (path, content_hash, parser_id, parser_version, parse_status, "
                        + "total_lines, comment_lines, blank_lines) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, meta.path());
            ps.setString(2, meta.contentHash());
            ps.setString(3, meta.parserId());
            ps.setString(4, meta.parserVersion());
            ps.setString(5, meta.parseStatus());
            ps.setInt(6, meta.totalLines());
            ps.setInt(7, meta.commentLines());
            ps.setInt(8, meta.blankLines());
            ps.executeUpdate();
        }
        try (PreparedStatement ent = connection.prepareStatement(
                     "INSERT INTO cache_entity (path, ord, id, kind, name, qualified_name, language, "
                             + "loc_file, s_line, e_line, s_col, e_col) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement attr = connection.prepareStatement(
                     "INSERT INTO cache_entity_attr (path, ord, attr_key, attr_val) VALUES (?,?,?,?)")) {
            int ord = 0;
            for (Entity e : entry.entities()) {
                ent.setString(1, meta.path());
                ent.setInt(2, ord);
                ent.setString(3, e.id());
                ent.setString(4, e.kind().name());
                ent.setString(5, e.name());
                ent.setString(6, e.qualifiedName());
                ent.setString(7, e.language());
                SourceLocation loc = e.location().orElse(null);
                ent.setString(8, loc != null ? loc.filePath() : null);
                ent.setInt(9, loc != null ? loc.startLine() : 0);
                ent.setInt(10, loc != null ? loc.endLine() : 0);
                ent.setInt(11, loc != null ? loc.startColumn() : 0);
                ent.setInt(12, loc != null ? loc.endColumn() : 0);
                ent.addBatch();
                for (var a : e.attributes().entrySet()) {
                    attr.setString(1, meta.path());
                    attr.setInt(2, ord);
                    attr.setString(3, a.getKey());
                    attr.setString(4, a.getValue());
                    attr.addBatch();
                }
                ord++;
            }
            ent.executeBatch();
            attr.executeBatch();
        }
        try (PreparedStatement rel = connection.prepareStatement(
                     "INSERT INTO cache_rel (path, ord, from_id, to_id, kind, resolved, status, "
                             + "loc_file, s_line, e_line) VALUES (?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement attr = connection.prepareStatement(
                     "INSERT INTO cache_rel_attr (path, ord, attr_key, attr_val) VALUES (?,?,?,?)")) {
            int ord = 0;
            for (Relationship r : entry.relationships()) {
                rel.setString(1, meta.path());
                rel.setInt(2, ord);
                rel.setString(3, r.fromId());
                rel.setString(4, r.toId());
                rel.setString(5, r.kind().name());
                rel.setBoolean(6, r.resolved());
                rel.setString(7, r.status().name());
                SourceLocation loc = r.location().orElse(null);
                rel.setString(8, loc != null ? loc.filePath() : null);
                rel.setInt(9, loc != null ? loc.startLine() : 0);
                rel.setInt(10, loc != null ? loc.endLine() : 0);
                rel.addBatch();
                for (var a : r.attributes().entrySet()) {
                    attr.setString(1, meta.path());
                    attr.setInt(2, ord);
                    attr.setString(3, a.getKey());
                    attr.setString(4, a.getValue());
                    attr.addBatch();
                }
                ord++;
            }
            rel.executeBatch();
            attr.executeBatch();
        }
    }

    private void pruneCache(Set<String> livePaths) throws SQLException {
        List<String> stale = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT path FROM cache_file")) {
            while (rs.next()) {
                String path = rs.getString(1);
                if (!livePaths.contains(path)) {
                    stale.add(path);
                }
            }
        }
        for (String path : stale) {
            for (String table : List.of("cache_file", "cache_entity", "cache_entity_attr",
                    "cache_rel", "cache_rel_attr")) {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE path = ?")) {
                    del.setString(1, path);
                    del.executeUpdate();
                }
            }
        }
    }

    // ---- model snapshot persistence ----

    /**
     * Persists the full model, replacing any prior contents, in one committed
     * transaction. Prefer {@link #persistCompletedScan} in the pipeline; this
     * remains for tests and standalone use.
     */
    public void saveModel(SoftwareModel model) {
        try {
            clearModelTables();
            insertModel(model);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IndexException("Cannot persist model", e);
        }
    }

    /** Replaces stored file hashes with the given snapshot (standalone/test use). */
    public void saveHashes(Map<String, String> pathToHash) {
        try {
            replaceHashes(pathToHash);
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IndexException("Cannot save file hashes", e);
        }
    }

    private void clearModelTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM relationship_attr");
            st.execute("DELETE FROM relationship");
            st.execute("DELETE FROM entity_attr");
            st.execute("DELETE FROM entity");
            st.execute("DELETE FROM diagnostic");
        }
    }

    private void replaceHashes(Map<String, String> pathToHash) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM file_hash");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO file_hash(path, hash) VALUES (?, ?)")) {
            for (var e : pathToHash.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertModel(SoftwareModel model) throws SQLException {
        try (PreparedStatement ent = connection.prepareStatement(
                     "INSERT INTO entity(id, kind, name, qualified_name, language, file_path, start_line, end_line) "
                             + "VALUES (?,?,?,?,?,?,?,?)");
             PreparedStatement attr = connection.prepareStatement(
                     "INSERT INTO entity_attr(entity_id, attr_key, attr_val) VALUES (?,?,?)")) {
            for (Entity e : model.entities()) {
                ent.setString(1, e.id());
                ent.setString(2, e.kind().name());
                ent.setString(3, e.name());
                ent.setString(4, e.qualifiedName());
                ent.setString(5, e.language());
                SourceLocation loc = e.location().orElse(null);
                ent.setString(6, loc != null ? loc.filePath() : null);
                ent.setInt(7, loc != null ? loc.startLine() : 0);
                ent.setInt(8, loc != null ? loc.endLine() : 0);
                ent.addBatch();
                for (var a : e.attributes().entrySet()) {
                    attr.setString(1, e.id());
                    attr.setString(2, a.getKey());
                    attr.setString(3, a.getValue());
                    attr.addBatch();
                }
            }
            ent.executeBatch();
            attr.executeBatch();
        }
        try (PreparedStatement rel = connection.prepareStatement(
                     "INSERT INTO relationship(id, from_id, to_id, kind, resolved, status, loc_file, s_line, e_line) "
                             + "VALUES (?,?,?,?,?,?,?,?,?)");
             PreparedStatement attr = connection.prepareStatement(
                     "INSERT INTO relationship_attr(rel_id, attr_key, attr_val) VALUES (?,?,?)")) {
            long relId = 1;
            for (Relationship r : model.relationships()) {
                rel.setLong(1, relId);
                rel.setString(2, r.fromId());
                rel.setString(3, r.toId());
                rel.setString(4, r.kind().name());
                rel.setBoolean(5, r.resolved());
                rel.setString(6, r.status().name());
                SourceLocation loc = r.location().orElse(null);
                rel.setString(7, loc != null ? loc.filePath() : null);
                rel.setInt(8, loc != null ? loc.startLine() : 0);
                rel.setInt(9, loc != null ? loc.endLine() : 0);
                rel.addBatch();
                for (var a : r.attributes().entrySet()) {
                    attr.setLong(1, relId);
                    attr.setString(2, a.getKey());
                    attr.setString(3, a.getValue());
                    attr.addBatch();
                }
                relId++;
            }
            rel.executeBatch();
            attr.executeBatch();
        }
        try (PreparedStatement diag = connection.prepareStatement(
                "INSERT INTO diagnostic(ord, code, message) VALUES (?,?,?)")) {
            int ord = 0;
            for (com.codeatlas.model.Diagnostic d : model.diagnostics()) {
                diag.setInt(1, ord++);
                diag.setString(2, d.code());
                diag.setString(3, d.message());
                diag.addBatch();
            }
            diag.executeBatch();
        }
    }

    /** Scan-time diagnostics (e.g. stable-id collisions) persisted with the snapshot. */
    public List<com.codeatlas.model.Diagnostic> loadDiagnostics() {
        List<com.codeatlas.model.Diagnostic> out = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT code, message FROM diagnostic ORDER BY ord")) {
            while (rs.next()) {
                out.add(new com.codeatlas.model.Diagnostic(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot load diagnostics", e);
        }
        return out;
    }

    /** Reconstructs the persisted model (entities, attributes, relationships with metadata). */
    public SoftwareModel loadModel() {
        SoftwareModel model = new SoftwareModel();
        Map<String, Entity.Builder> builders = new HashMap<>();
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, kind, name, qualified_name, language, file_path, start_line, end_line FROM entity")) {
                while (rs.next()) {
                    String filePath = rs.getString("file_path");
                    Entity.Builder b = Entity.builder(EntityKind.valueOf(rs.getString("kind")), rs.getString("name"))
                            .id(rs.getString("id"))
                            .qualifiedName(rs.getString("qualified_name"))
                            .language(rs.getString("language"));
                    if (filePath != null) {
                        b.location(new SourceLocation(filePath, rs.getInt("start_line"), rs.getInt("end_line"), 0, 0));
                    }
                    builders.put(rs.getString("id"), b);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT entity_id, attr_key, attr_val FROM entity_attr")) {
                while (rs.next()) {
                    Entity.Builder b = builders.get(rs.getString(1));
                    if (b != null) {
                        b.attribute(rs.getString(2), rs.getString(3));
                    }
                }
            }
            builders.values().forEach(b -> model.addEntity(b.build()));

            Map<Long, Relationship.Builder> rels = new HashMap<>();
            List<Long> order = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT id, from_id, to_id, kind, resolved, status, loc_file, s_line, e_line "
                            + "FROM relationship ORDER BY id")) {
                while (rs.next()) {
                    Relationship.Builder b = Relationship.builder(
                                    RelationshipKind.valueOf(rs.getString("kind")),
                                    rs.getString("from_id"), rs.getString("to_id"))
                            .resolved(rs.getBoolean("resolved"))
                            .status(ResolutionStatus.valueOf(rs.getString("status")));
                    String locFile = rs.getString("loc_file");
                    if (locFile != null) {
                        b.location(new SourceLocation(locFile, rs.getInt("s_line"), rs.getInt("e_line"), 0, 0));
                    }
                    long id = rs.getLong("id");
                    rels.put(id, b);
                    order.add(id);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT rel_id, attr_key, attr_val FROM relationship_attr")) {
                while (rs.next()) {
                    Relationship.Builder b = rels.get(rs.getLong(1));
                    if (b != null) {
                        b.attribute(rs.getString(2), rs.getString(3));
                    }
                }
            }
            for (long id : order) {
                model.addRelationship(rels.get(id).build());
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot load model", e);
        }
        return model;
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("Rollback failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Error closing index: {}", e.getMessage());
        }
    }
}
