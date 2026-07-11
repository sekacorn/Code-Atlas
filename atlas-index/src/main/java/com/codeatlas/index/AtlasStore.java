package com.codeatlas.index;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local, offline store for the software model, backed by H2.
 *
 * <p>Per the platform spec it defaults to an <em>in-memory</em> database when no
 * path is given, and a file-backed database (no server, no admin) when one is.
 * It persists entities, their attributes, relationships and per-file content
 * hashes, and computes {@link ChangeSet}s to drive incremental updates.
 *
 * <p>The store is deliberately schema-simple and dependency-light: attributes live
 * in a narrow key/value table rather than serialised blobs, so the index stays
 * queryable and auditable with plain SQL.
 */
public final class AtlasStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AtlasStore.class);

    private final Connection connection;
    private final String url;

    private AtlasStore(String url) {
        this.url = url;
        try {
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(false);
            initSchema();
        } catch (SQLException e) {
            throw new IndexException("Cannot open index at " + url, e);
        }
    }

    /** Opens a transient in-memory index (default when no path is available). */
    public static AtlasStore inMemory() {
        return new AtlasStore("jdbc:h2:mem:atlas;DB_CLOSE_DELAY=-1");
    }

    /** Opens (or creates) a file-backed index at {@code dbPath} &mdash; no server needed. */
    public static AtlasStore atPath(Path dbPath) {
        return new AtlasStore("jdbc:h2:file:" + dbPath.toAbsolutePath() + ";AUTO_SERVER=FALSE");
    }

    private void initSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS entity (
                        id            VARCHAR PRIMARY KEY,
                        kind          VARCHAR NOT NULL,
                        name          VARCHAR NOT NULL,
                        qualified_name VARCHAR NOT NULL,
                        language      VARCHAR NOT NULL,
                        file_path     VARCHAR,
                        start_line    INT,
                        end_line      INT
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
                        from_id  VARCHAR NOT NULL,
                        to_id    VARCHAR NOT NULL,
                        kind     VARCHAR NOT NULL,
                        resolved BOOLEAN NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS file_hash (
                        path VARCHAR PRIMARY KEY,
                        hash VARCHAR NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rel_from ON relationship(from_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_rel_to ON relationship(to_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entity_kind ON entity(kind)");
        }
        connection.commit();
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

    // ---- persistence ----

    /** Replaces stored file hashes with the given snapshot. */
    public void saveHashes(Map<String, String> pathToHash) {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM file_hash");
        } catch (SQLException e) {
            throw new IndexException("Cannot clear file hashes", e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO file_hash(path, hash) VALUES (?, ?)")) {
            for (var e : pathToHash.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            throw new IndexException("Cannot save file hashes", e);
        }
    }

    /** Persists the full model, replacing any prior contents. */
    public void saveModel(SoftwareModel model) {
        try (Statement st = connection.createStatement()) {
            st.execute("DELETE FROM entity_attr");
            st.execute("DELETE FROM entity");
            st.execute("DELETE FROM relationship");
        } catch (SQLException e) {
            throw new IndexException("Cannot clear model tables", e);
        }
        try (PreparedStatement ent = connection.prepareStatement(
                     "INSERT INTO entity(id, kind, name, qualified_name, language, file_path, start_line, end_line) "
                             + "VALUES (?,?,?,?,?,?,?,?)");
             PreparedStatement attr = connection.prepareStatement(
                     "INSERT INTO entity_attr(entity_id, attr_key, attr_val) VALUES (?,?,?)");
             PreparedStatement rel = connection.prepareStatement(
                     "INSERT INTO relationship(from_id, to_id, kind, resolved) VALUES (?,?,?,?)")) {

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
            for (Relationship r : model.relationships()) {
                rel.setString(1, r.fromId());
                rel.setString(2, r.toId());
                rel.setString(3, r.kind().name());
                rel.setBoolean(4, r.resolved());
                rel.addBatch();
            }
            ent.executeBatch();
            attr.executeBatch();
            rel.executeBatch();
            connection.commit();
            log.info("Persisted {} entities and {} relationships to {}",
                    model.entityCount(), model.relationshipCount(), url);
        } catch (SQLException e) {
            throw new IndexException("Cannot persist model", e);
        }
    }

    /** Reconstructs a model from the store (entities, attributes, relationships). */
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
            try (ResultSet rs = st.executeQuery("SELECT from_id, to_id, kind, resolved FROM relationship")) {
                while (rs.next()) {
                    model.addRelationship(Relationship.builder(
                                    RelationshipKind.valueOf(rs.getString("kind")),
                                    rs.getString("from_id"), rs.getString("to_id"))
                            .resolved(rs.getBoolean("resolved")).build());
                }
            }
        } catch (SQLException e) {
            throw new IndexException("Cannot load model", e);
        }
        return model;
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
