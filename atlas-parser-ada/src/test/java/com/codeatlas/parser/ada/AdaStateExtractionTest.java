package com.codeatlas.parser.ada;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
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

/** Locks the Ada parser's package-state, call-detail and type-flow extraction. */
class AdaStateExtractionTest {

    private final AdaLanguageParser parser = new AdaLanguageParser();

    private ParseResult parse(String name, String content) {
        return parser.parse(new ParseRequest(name, Path.of(name), content, "hash", "ada"));
    }

    @Test
    void extractsPackageStateButNotLocalsOrRecordComponents() {
        ParseResult r = parse("state.adb", """
                package body State is

                   Counter : Natural := 0;
                   Limit   : constant Natural := 10;

                   type Pair is record
                      First  : Natural;
                      Second : Natural;
                   end record;

                   procedure Step is
                      Scratch : Natural;
                   begin
                      Scratch := 1;
                      Counter := Counter + Scratch;
                   end Step;

                end State;
                """);

        List<Entity> vars = r.entities().stream()
                .filter(e -> e.kind() == EntityKind.VARIABLE).toList();
        assertEquals(2, vars.size(), "only package-level objects are state: " + vars);
        Entity counter = vars.stream().filter(v -> v.name().equals("Counter")).findFirst().orElseThrow();
        assertEquals("State.Counter", counter.qualifiedName());
        assertEquals("Natural", counter.attribute("variableType").orElseThrow());
        assertTrue(vars.stream().anyMatch(v -> v.name().equals("Limit")
                && v.boolAttribute("constant", false)), "constants are flagged");
        assertFalse(vars.stream().anyMatch(v -> v.name().equals("Scratch")), "locals excluded");
        assertFalse(vars.stream().anyMatch(v -> v.name().equals("First")), "record components excluded");

        // The local assignment is skipped; the state assignment becomes a candidate.
        List<Relationship> writes = r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.WRITES_TO).toList();
        assertEquals(1, writes.size(), "only the state write is a candidate: " + writes);
        assertEquals("Counter", writes.get(0).attributes().get(EvidenceKeys.STATE_NAME));
        assertEquals("State", writes.get(0).attributes().get(EvidenceKeys.ENCLOSING_PACKAGE));
        assertTrue(writes.get(0).location().isPresent(), "write carries its source line");

        // Counter appears on the right-hand side too: a read candidate.
        assertTrue(r.relationships().stream().anyMatch(x ->
                        x.kind() == RelationshipKind.READS_FROM
                                && "Counter".equals(x.attributes().get(EvidenceKeys.STATE_NAME))),
                "RHS occurrence must yield a read candidate");
    }

    @Test
    void qualifiedCallsCarryFullNamesAndLocations() {
        ParseResult r = parse("ops.adb", """
                with Mission_Data;
                package body Ops is
                   procedure Run is
                   begin
                      Mission_Data.Load_Route;
                      Compute (1);
                   end Run;
                end Ops;
                """);
        Relationship qualified = r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.CALLS
                        && "Load_Route".equals(x.attributes().get(EvidenceKeys.CALL_NAME)))
                .findFirst().orElseThrow();
        assertEquals("Mission_Data.Load_Route",
                qualified.attributes().get(EvidenceKeys.QUALIFIED_CALL_NAME));
        assertTrue(qualified.location().isPresent(), "calls carry their source line");

        Relationship plain = r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.CALLS
                        && "Compute".equals(x.attributes().get(EvidenceKeys.CALL_NAME)))
                .findFirst().orElseThrow();
        assertFalse(plain.attributes().containsKey(EvidenceKeys.QUALIFIED_CALL_NAME));
    }

    @Test
    void functionsExposeParameterAndReturnTypes() {
        ParseResult r = parse("conv.ads", """
                package Conv is
                   function To_Meters (Feet : Float) return Float;
                   function Transform (Raw : Raw_Data) return Route_Type;
                end Conv;
                """);
        Entity transform = r.entities().stream()
                .filter(e -> e.kind() == EntityKind.FUNCTION && e.name().equals("Transform"))
                .findFirst().orElseThrow();
        assertEquals("Raw_Data", transform.attribute(Entity.Attributes.PARAM_TYPES).orElseThrow());
        assertEquals("Route_Type", transform.attribute(Entity.Attributes.RETURN_TYPE).orElseThrow());
    }

    @Test
    void qualifiedStateWritesKeepTheFullTarget() {
        ParseResult r = parse("client.adb", """
                with Store;
                package body Client is
                   procedure Bump is
                   begin
                      Store.Total := Store.Total + 1;
                   end Bump;
                end Client;
                """);
        Relationship write = r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.WRITES_TO).findFirst().orElseThrow();
        assertEquals("Store.Total", write.attributes().get(EvidenceKeys.STATE_NAME));
        assertTrue(r.relationships().stream().anyMatch(x ->
                        x.kind() == RelationshipKind.READS_FROM
                                && "Store.Total".equals(x.attributes().get(EvidenceKeys.STATE_NAME))),
                "the RHS qualified reference is a read candidate");
    }
}
