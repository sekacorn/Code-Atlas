package com.codeatlas.parser.ada;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaLanguageParserTest {

    private final AdaLanguageParser parser = new AdaLanguageParser();

    private ParseResult parse(String name, String content) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return parser.parse(new ParseRequest(name, Path.of(name), content, "hash", "ada"));
    }

    @Test
    void extractsPackageSpecAndSubprograms() {
        String ads = """
                with Ada.Text_IO;
                with Interfaces;

                package Navigation is
                   type Route is record
                      Length : Natural;
                   end record;

                   function Calculate_Mission_Route (Start : Integer) return Integer;

                   procedure Reset;
                end Navigation;
                """;
        ParseResult r = parse("navigation.ads", ads);

        Entity pkg = r.entities().stream().filter(e -> e.kind() == EntityKind.PACKAGE)
                .findFirst().orElseThrow();
        assertEquals("Navigation", pkg.qualifiedName());

        assertTrue(r.entities().stream().anyMatch(e ->
                e.kind() == EntityKind.FUNCTION && e.name().equals("Calculate_Mission_Route")));
        assertTrue(r.entities().stream().anyMatch(e ->
                e.kind() == EntityKind.PROCEDURE && e.name().equals("Reset")));
        assertTrue(r.entities().stream().anyMatch(e -> e.kind() == EntityKind.TYPE));

        // Two with-clauses -> two import edges.
        long imports = r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.IMPORTS).count();
        assertEquals(2, imports);
    }

    @Test
    void computesComplexityAndCallsInBody() {
        String adb = """
                package body Navigation is
                   procedure Reset is
                   begin
                      Clear_State;
                   end Reset;

                   function Calculate_Mission_Route (Start : Integer) return Integer is
                      Result : Integer := Start;
                   begin
                      if Start > 0 then
                         for I in 1 .. Start loop
                            Result := Result + Step (I);
                         end loop;
                      end if;
                      return Result;
                   end Calculate_Mission_Route;
                end Navigation;
                """;
        ParseResult r = parse("navigation.adb", adb);

        Entity route = r.entities().stream()
                .filter(e -> e.kind() == EntityKind.FUNCTION && e.name().equals("Calculate_Mission_Route"))
                .findFirst().orElseThrow();
        // base 1 + if + for = 3
        assertEquals(3, route.intAttribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, -1));

        assertTrue(r.relationships().stream().anyMatch(x ->
                x.kind() == RelationshipKind.CALLS && "Step".equals(x.attributes().get("callName"))));
        assertTrue(r.relationships().stream().anyMatch(x ->
                x.kind() == RelationshipKind.CALLS && "Clear_State".equals(x.attributes().get("callName"))));
    }

    @Test
    void capturesSparkContracts() {
        String ads = """
                package Math_Ops is
                   function Increment (Input : Integer) return Integer
                      with Pre  => Input > 0,
                           Post => Increment'Result >= Input;
                end Math_Ops;
                """;
        ParseResult r = parse("math_ops.ads", ads);
        Entity inc = r.entities().stream()
                .filter(e -> e.kind() == EntityKind.FUNCTION && e.name().equals("Increment"))
                .findFirst().orElseThrow();
        assertTrue(inc.attribute("sparkPrecondition").orElse("").contains("Input > 0"));
        assertTrue(inc.attribute("sparkPostcondition").orElse("").contains(">= Input"));
    }
}
