package com.codeatlas.parser.sql;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema extraction from DDL: tables, views, columns and foreign keys. */
class SqlParserTest {

    private final SqlParser parser = new SqlParser();

    private ParseResult parse(String content) {
        return parser.parse(new ParseRequest("db/schema.sql", Path.of("db/schema.sql"),
                content, "h", "sql"));
    }

    private Entity entity(ParseResult r, String id) {
        return r.entities().stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + id + " in "
                        + r.entities().stream().map(Entity::id).toList()));
    }

    private List<Relationship> of(ParseResult r, RelationshipKind kind) {
        return r.relationships().stream().filter(x -> x.kind() == kind).toList();
    }

    @Test
    void claimsOnlySqlFiles() {
        assertTrue(parser.supports(new ParseRequest("a.sql", Path.of("a.sql"), "", "h", "sql")));
        assertTrue(parser.supports(new ParseRequest("a.ddl", Path.of("a.ddl"), "", "h", "sql")));
        assertFalse(parser.supports(new ParseRequest("a.java", Path.of("a.java"), "", "h", "java")));
    }

    @Test
    void createTableBecomesADeclaredTableWithColumns() {
        ParseResult r = parse("""
                CREATE TABLE customer (
                    id BIGINT NOT NULL PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    notes TEXT
                );
                """);
        Entity table = entity(r, "sql:table:customer");
        assertEquals(EntityKind.DATABASE_OBJECT, table.kind());
        assertEquals("table", table.attribute("dbObjectType").orElseThrow());
        assertEquals("3", table.attribute("columnCount").orElseThrow());
        assertTrue(table.attribute(SqlParser.DECLARED_IN).orElseThrow().startsWith("db/schema.sql:"),
                "a declared table cites the DDL that declares it");
        assertTrue(table.location().isPresent(), "a declaration has a source location");

        Entity id = entity(r, "sql:field:customer.id");
        assertEquals("BIGINT", id.attribute("sqlType").orElseThrow());
        assertEquals("true", id.attribute("primaryKey").orElseThrow());
        assertEquals("VARCHAR(255)", entity(r, "sql:field:customer.name").attribute("sqlType").orElseThrow());
        assertEquals("false", entity(r, "sql:field:customer.notes").attribute("notNull").orElseThrow());

        assertTrue(of(r, RelationshipKind.CONTAINS).stream()
                .anyMatch(c -> c.fromId().equals("sql:table:customer")
                        && c.toId().equals("sql:field:customer.id")), "the table contains its columns");
    }

    @Test
    void tableLevelPrimaryKeyIsFoldedOntoItsColumn() {
        ParseResult r = parse("""
                CREATE TABLE orders (
                    order_id BIGINT,
                    total DECIMAL(10,2),
                    PRIMARY KEY (order_id)
                );
                """);
        assertEquals("true", entity(r, "sql:field:orders.order_id").attribute("primaryKey").orElseThrow());
        assertEquals("false", entity(r, "sql:field:orders.total").attribute("primaryKey").orElseThrow());
        // The comma inside DECIMAL(10,2) must not split the column list.
        assertEquals("DECIMAL(10,2)", entity(r, "sql:field:orders.total").attribute("sqlType").orElseThrow());
        assertEquals("2", entity(r, "sql:table:orders").attribute("columnCount").orElseThrow());
    }

    @Test
    void foreignKeysReferenceTheTableTheyPointAt() {
        ParseResult r = parse("""
                CREATE TABLE mission (
                    id BIGINT PRIMARY KEY,
                    customer_id BIGINT REFERENCES customer(id),
                    CONSTRAINT fk_route FOREIGN KEY (route_id) REFERENCES route(id)
                );
                ALTER TABLE mission ADD CONSTRAINT fk_owner FOREIGN KEY (owner_id) REFERENCES owner(id);
                """);
        List<Relationship> fks = of(r, RelationshipKind.REFERENCES);
        assertEquals(3, fks.size(), "inline, table-level and ALTER foreign keys are all recorded");
        assertTrue(fks.stream().allMatch(f -> f.fromId().equals("sql:table:mission")));
        assertTrue(fks.stream().noneMatch(Relationship::resolved), "the target resolves later, in the Linker");
        assertTrue(fks.stream().anyMatch(f -> f.attributes().get("typeName").equals("customer")));
        assertTrue(fks.stream().anyMatch(f -> f.attributes().get("typeName").equals("route")));
        assertTrue(fks.stream().anyMatch(f -> f.attributes().get("typeName").equals("owner")
                        && f.attributes().get("foreignKeyColumn").equals("owner_id")),
                "an ALTER TABLE key is attributed to the table it alters");
    }

    @Test
    void identifiersAreNormalizedAndCommentsIgnored() {
        ParseResult r = parse("""
                -- CREATE TABLE commented_out (id INT);
                /* a block comment
                   CREATE TABLE also_not_real (id INT); */
                CREATE TABLE IF NOT EXISTS "Public"."Customer" (
                    "Id" BIGINT PRIMARY KEY
                );
                """);
        Entity t = entity(r, "sql:table:customer");
        assertEquals("Customer", t.attribute("declaredName").orElseThrow(),
                "the declared spelling is kept as evidence, the id is normalized");
        assertTrue(r.entities().stream().noneMatch(e -> e.id().contains("commented_out")));
        assertTrue(r.entities().stream().noneMatch(e -> e.id().contains("also_not_real")));
        assertTrue(r.entities().stream().anyMatch(e -> e.id().equals("sql:field:customer.id")));
    }

    @Test
    void viewsAreRecordedAsObjectsNotQueries() {
        ParseResult r = parse("CREATE VIEW active_customer AS SELECT id FROM customer WHERE active = 1;");
        Entity v = entity(r, "sql:table:active_customer");
        assertEquals("view", v.attribute("dbObjectType").orElseThrow());
        // The view's query is not analysed, so no reference to customer is claimed.
        assertTrue(of(r, RelationshipKind.REFERENCES).isEmpty(),
                "a view's underlying query is not analysed, and no edge is invented from it");
    }

    @Test
    void filesWithNoSchemaSaySoAndMalformedSqlNeverThrows() {
        ParseResult none = parse("SELECT * FROM customer;");
        assertTrue(none.entities().isEmpty(), "a query declares no schema");
        assertTrue(none.issues().stream().anyMatch(i -> i.message().contains("nothing recorded")));

        ParseResult broken = parse("CREATE TABLE oops (");
        assertTrue(broken.entities().isEmpty(), "an unterminated declaration invents nothing");
    }
}
