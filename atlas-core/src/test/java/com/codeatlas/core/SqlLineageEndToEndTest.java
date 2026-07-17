package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Literal SQL in Java code becoming data lineage. Before this, a table was only
 * reachable through a JPA mapping, so a JDBC DAO's reads and writes were invisible.
 */
class SqlLineageEndToEndTest {

    private static void writeFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("db"));
        Files.createDirectories(repo.resolve("src/com/example"));

        Files.writeString(repo.resolve("db/schema.sql"), """
                CREATE TABLE customer (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(255)
                );
                CREATE TABLE audit_log (
                    id BIGINT PRIMARY KEY,
                    msg VARCHAR(255)
                );
                """);

        Files.writeString(repo.resolve("src/com/example/CustomerDao.java"), """
                package com.example;

                public class CustomerDao {

                    public String findName(long id) {
                        String sql = "SELECT name FROM customer WHERE id = ?";
                        return execute(sql);
                    }

                    public void audit(String message) {
                        String sql = "INSERT INTO audit_log (msg) VALUES (?)";
                        execute(sql);
                    }

                    public String search(String filter) {
                        String sql = "SELECT * FROM customer WHERE " + filter;
                        return execute(sql);
                    }

                    public void touchUnknown() {
                        String sql = "DELETE FROM legacy_cache WHERE stale = 1";
                        execute(sql);
                    }

                    private String execute(String sql) { return sql; }
                }
                """);
    }

    private SoftwareModel scan(Path repo) throws IOException {
        writeFixture(repo);
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults()).model();
    }

    private Optional<Relationship> edge(SoftwareModel m, String from, RelationshipKind kind, String to) {
        return m.outgoing(from).stream()
                .filter(r -> r.kind() == kind && r.toId().equals(to)).findFirst();
    }

    @Test
    void jdbcReadsAndWritesReachTheDeclaredTables(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        String find = "java:method:com.example.CustomerDao#findName(long)";
        String audit = "java:method:com.example.CustomerDao#audit(String)";

        Relationship read = edge(model, find, RelationshipKind.READS_FROM, "sql:table:customer")
                .orElseThrow(() -> new AssertionError("the SELECT should read the customer table"));
        assertTrue(read.resolved());
        assertEquals("ATLAS-LINEAGE-SQL-001", read.attributes().get(EvidenceKeys.RULE_ID));
        assertEquals("0.95", read.attributes().get(EvidenceKeys.CONFIDENCE));

        assertTrue(edge(model, audit, RelationshipKind.WRITES_TO, "sql:table:audit_log").isPresent(),
                "the INSERT should write the audit_log table");
    }

    @Test
    void runtimeAssembledSqlIsLowerConfidenceAndLabelledInferred(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        Relationship r = edge(model, "java:method:com.example.CustomerDao#search(String)",
                RelationshipKind.READS_FROM, "sql:table:customer")
                .orElseThrow(() -> new AssertionError("the literal fragment still names customer"));
        assertEquals("ATLAS-LINEAGE-SQL-002", r.attributes().get(EvidenceKeys.RULE_ID));
        assertEquals("0.60", r.attributes().get(EvidenceKeys.CONFIDENCE));
        assertEquals("true", r.attributes().get(EvidenceKeys.INFERRED),
                "a statement assembled at runtime was never fully visible, and says so");
    }

    @Test
    void sqlNamingATableNoSchemaDeclaresStillModelsItButClaimsNoDeclaration(@TempDir Path repo)
            throws IOException {
        SoftwareModel model = scan(repo);
        Entity legacy = model.entity("sql:table:legacy_cache").orElseThrow(
                () -> new AssertionError("SQL naming a table is evidence the table exists"));
        assertTrue(legacy.attribute("declaredIn").isEmpty(),
                "no parsed DDL declares it, so it claims no declaration");
        assertTrue(edge(model, "java:method:com.example.CustomerDao#touchUnknown()",
                        RelationshipKind.WRITES_TO, "sql:table:legacy_cache").isPresent());
        // The declared tables remain distinguishable by their declaration.
        assertTrue(model.entity("sql:table:customer").orElseThrow()
                .attribute("declaredIn").isPresent());
    }

    @Test
    void tablesTouchedBySqlAreTraceableUpstreamToTheirCallers(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        // The whole point: the table now has code attached to it in the graph.
        assertTrue(model.incoming("sql:table:customer").stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.READS_FROM
                                && r.fromId().contains("CustomerDao")),
                "the customer table knows which code reads it");
    }
}
