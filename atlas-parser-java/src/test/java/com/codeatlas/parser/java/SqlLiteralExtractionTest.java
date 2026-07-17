package com.codeatlas.parser.java;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which tables a method's literal SQL reads and writes, derived without storing the SQL. */
class SqlLiteralExtractionTest {

    private final JavaLanguageParser parser = new JavaLanguageParser();

    private Entity method(String body, String name) {
        ParseResult r = parser.parse(new ParseRequest("src/Dao.java", Path.of("src/Dao.java"),
                "class Dao {\n" + body + "\n}\n", "h", "java"));
        return r.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void selectReadsItsTables() {
        Entity m = method("""
                void find() {
                    String sql = "SELECT c.id, o.total FROM customer c JOIN orders o ON o.cid = c.id";
                }
                """, "find");
        assertEquals("customer,orders", m.attribute(Entity.Attributes.SQL_READS).orElseThrow());
        assertTrue(m.attribute(Entity.Attributes.SQL_WRITES).isEmpty(), "a SELECT writes nothing");
    }

    @Test
    void insertUpdateAndDeleteWriteTheirTarget() {
        assertEquals("audit_log", method("""
                void log() { String s = "INSERT INTO audit_log (msg) VALUES (?)"; }
                """, "log").attribute(Entity.Attributes.SQL_WRITES).orElseThrow());

        assertEquals("customer", method("""
                void rename() { String s = "UPDATE customer SET name = ? WHERE id = ?"; }
                """, "rename").attribute(Entity.Attributes.SQL_WRITES).orElseThrow());

        Entity del = method("""
                void purge() { String s = "DELETE FROM customer WHERE id = ?"; }
                """, "purge");
        assertEquals("customer", del.attribute(Entity.Attributes.SQL_WRITES).orElseThrow());
        assertTrue(del.attribute(Entity.Attributes.SQL_READS).isEmpty(),
                "the FROM in DELETE FROM names the delete target, not a read");
    }

    @Test
    void insertSelectWritesTheTargetAndReadsTheSource() {
        Entity m = method("""
                void archive() {
                    String s = "INSERT INTO archive SELECT * FROM customer WHERE old = 1";
                }
                """, "archive");
        assertEquals("archive", m.attribute(Entity.Attributes.SQL_WRITES).orElseThrow());
        assertEquals("customer", m.attribute(Entity.Attributes.SQL_READS).orElseThrow());
    }

    @Test
    void runtimeAssembledSqlIsMarkedDynamic() {
        Entity m = method("""
                void search(String where) {
                    String sql = "SELECT * FROM customer WHERE " + where;
                }
                """, "search");
        assertEquals("customer", m.attribute(Entity.Attributes.SQL_READS).orElseThrow(),
                "the literal fragment still names a real table");
        assertTrue(m.boolAttribute(Entity.Attributes.SQL_DYNAMIC, false),
                "but the statement was assembled at runtime and says so");
    }

    @Test
    void queryAnnotationsCountAsLiteralSql() {
        Entity m = method("""
                @Query("SELECT m FROM mission m WHERE m.active = true")
                java.util.List<Object> active() { return null; }
                """, "active");
        assertEquals("mission", m.attribute(Entity.Attributes.SQL_READS).orElseThrow());
    }

    @Test
    void proseThatOpensWithSelectDoesNotInventATable() {
        // This sentence parses as "SELECT … FROM the", and a naive scanner would
        // invent a table called "the". Nothing may be invented from prose.
        Entity m = method("""
                void greet() {
                    String s = "select a nice greeting from the list";
                }
                """, "greet");
        assertFalse(m.attribute(Entity.Attributes.SQL_READS).isPresent(),
                "an English article is never a table name: " + m.attributes());
        assertFalse(m.attribute(Entity.Attributes.SQL_WRITES).isPresent());
    }

    @Test
    void ordinaryStringsProduceNoSqlFacts() {
        Entity plain = method("""
                void plain() { String t = "hello world"; }
                """, "plain");
        assertTrue(plain.attribute(Entity.Attributes.SQL_READS).isEmpty(),
                "a non-SQL string produces no table facts");
        assertTrue(plain.attribute(Entity.Attributes.SQL_DYNAMIC).isEmpty());
    }

    @Test
    void methodsWithoutSqlCarryNoSqlAttributes() {
        Entity m = method("void compute() { int x = 1 + 2; }", "compute");
        assertTrue(m.attribute(Entity.Attributes.SQL_READS).isEmpty());
        assertTrue(m.attribute(Entity.Attributes.SQL_WRITES).isEmpty());
    }
}
