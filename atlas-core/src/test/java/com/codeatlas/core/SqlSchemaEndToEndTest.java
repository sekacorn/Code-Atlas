package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declared schema meeting the code that maps to it. The point of parsing DDL is
 * that a table stops being a name inferred from JPA naming conventions and becomes
 * a declaration with a file and line — while a mapping to a table no schema
 * declares stays visible as a gap.
 */
class SqlSchemaEndToEndTest {

    /** A JPA entity mapped to a declared table, plus one mapped to an undeclared table. */
    private static void writeFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("db"));
        Files.createDirectories(repo.resolve("src/com/example"));

        Files.writeString(repo.resolve("db/schema.sql"), """
                -- The schema of record.
                CREATE TABLE customer (
                    id BIGINT NOT NULL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                );

                CREATE TABLE mission (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT NOT NULL,
                    CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customer(id)
                );

                ALTER TABLE mission ADD CONSTRAINT fk_ghost FOREIGN KEY (ghost_id) REFERENCES ghost(id);
                """);

        Files.writeString(repo.resolve("src/com/example/CustomerEntity.java"), """
                package com.example;

                @Entity
                @Table(name = "customer")
                public class CustomerEntity {
                    @Id
                    private Long id;
                }
                """);
        // Maps to "auditlog" by naming convention; no DDL declares it.
        Files.writeString(repo.resolve("src/com/example/AuditLog.java"), """
                package com.example;

                @Entity
                public class AuditLog {
                    @Id
                    private Long id;
                }
                """);
    }

    private SoftwareModel scan(Path repo) throws IOException {
        writeFixture(repo);
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults()).model();
    }

    @Test
    void aDeclaredTableAndTheEntityMappedToItAreTheSameTable(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        Entity customer = model.entity("sql:table:customer").orElseThrow();

        // One entity, carrying both the declaration and the mapping - not two rival
        // tables, and no stable-id collision.
        assertTrue(customer.attribute("declaredIn").orElseThrow().startsWith("db/schema.sql:"),
                "the table carries where it is declared");
        assertTrue(customer.location().isPresent(), "and the declaration's location");
        assertTrue(model.diagnostics().stream().noneMatch(d -> d.message().contains("sql:table:customer")),
                "a table that is both declared and mapped is not a collision");
        assertTrue(model.incoming("sql:table:customer").stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.MAPS_TO
                                && r.fromId().equals("java:type:com.example.CustomerEntity")),
                "the JPA mapping still points at it");
        assertEquals(1, model.entitiesOfKind(EntityKind.DATABASE_OBJECT).stream()
                .filter(e -> e.name().equals("customer")).count());
    }

    @Test
    void aMappingToATableNoSchemaDeclaresStaysVisibleAsAGap(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        Entity audit = model.entity("sql:table:auditlog").orElseThrow();
        assertTrue(audit.attribute("declaredIn").isEmpty(),
                "no DDL declares this table, so it claims no declaration");
        assertTrue(audit.location().isEmpty(), "an inferred table has no source location to cite");
        assertTrue(model.incoming("sql:table:auditlog").stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.MAPS_TO),
                "the mapping is still recorded - the gap is that the schema does not declare it");
    }

    @Test
    void columnsAndForeignKeysEnterTheModel(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        assertTrue(model.entity("sql:field:customer.id").isPresent(), "columns are modeled");
        assertEquals("true", model.entity("sql:field:customer.id").orElseThrow()
                .attribute("primaryKey").orElseThrow());

        assertTrue(model.outgoing("sql:table:mission").stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.REFERENCES && r.resolved()
                                && r.toId().equals("sql:table:customer")),
                "a foreign key to a declared table resolves to it");
    }

    @Test
    void aForeignKeyToAnUndeclaredTableStaysUnresolved(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        assertTrue(model.outgoing("sql:table:mission").stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.REFERENCES && !r.resolved()
                                && "ghost".equals(r.attributes().get("typeName"))),
                "a key pointing at a table nothing declares is an honest unresolved reference");
        assertFalse(model.entity("sql:table:ghost").isPresent(),
                "and no table is fabricated for it");
    }
}
