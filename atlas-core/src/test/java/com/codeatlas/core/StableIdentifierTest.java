package com.codeatlas.core;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of stable identifiers and Ada spec/body unification across the
 * whole pipeline.
 */
class StableIdentifierTest {

    private static void writeAdaFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("ada"));
        Files.writeString(repo.resolve("ada/navigation.ads"), """
                package Navigation is
                   type Route_Record is record
                      Length : Natural;
                   end record;

                   function Find_Path (Start : Integer) return Integer;
                   function Find_Path (Start : Integer; Goal : Integer) return Integer;
                   procedure Reset;
                end Navigation;
                """);
        Files.writeString(repo.resolve("ada/navigation.adb"), """
                package body Navigation is
                   function Find_Path (Start : Integer) return Integer is
                   begin
                      return Start;
                   end Find_Path;
                   function Find_Path (Start : Integer; Goal : Integer) return Integer is
                   begin
                      return Start + Goal;
                   end Find_Path;
                   procedure Reset is
                   begin
                      null;
                   end Reset;
                   procedure Unused_Internal is
                   begin
                      null;
                   end Unused_Internal;
                end Navigation;
                """);
        // A child package (spec only) must remain a distinct entity.
        Files.writeString(repo.resolve("ada/navigation-sensors.ads"), """
                package Navigation.Sensors is
                   procedure Init;
                end Navigation.Sensors;
                """);
    }

    @Test
    void adaSpecAndBodyMergeIntoOneIdentityWithBothEvidences(@TempDir Path repo) throws IOException {
        writeAdaFixture(repo);
        SoftwareModel model = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.defaults()).model();

        // Exactly one Navigation package (spec+body merged), plus the child package.
        List<Entity> packages = model.entitiesOfKind(EntityKind.PACKAGE);
        Entity nav = byId(model, "ada:package:Navigation");
        assertTrue(nav.boolAttribute(Entity.Attributes.HAS_SPEC, false), "spec evidence retained");
        assertTrue(nav.boolAttribute(Entity.Attributes.HAS_BODY, false), "body evidence retained");
        assertTrue(nav.attribute(Entity.Attributes.SPEC_LOCATION).orElse("").contains("navigation.ads"));
        assertTrue(nav.attribute(Entity.Attributes.BODY_LOCATION).orElse("").contains("navigation.adb"));

        // Child package is distinct and spec-only.
        Entity sensors = byId(model, "ada:package:Navigation.Sensors");
        assertTrue(sensors.boolAttribute(Entity.Attributes.HAS_SPEC, false));
        assertFalse(sensors.boolAttribute(Entity.Attributes.HAS_BODY, false), "child has no body");
        assertEquals(2, packages.size(), "Navigation and Navigation.Sensors are distinct");

        // Overloaded functions keep distinct identities.
        assertTrue(hasEntity(model, "ada:function:Navigation.Find_Path(Integer)"));
        assertTrue(hasEntity(model, "ada:function:Navigation.Find_Path(Integer,Integer)"));

        // Subprograms are linked to the merged package.
        boolean resetLinked = model.outgoing(nav.id()).stream()
                .anyMatch(r -> r.kind() == RelationshipKind.CONTAINS
                        && r.toId().equals("ada:procedure:Navigation.Reset"));
        assertTrue(resetLinked, "Reset must be contained by the merged package");

        // A body-only subprogram is honestly represented and surfaces as dead code.
        Entity orphan = byId(model, "ada:procedure:Navigation.Unused_Internal");
        assertTrue(orphan.boolAttribute(Entity.Attributes.HAS_BODY, false));
        assertFalse(orphan.boolAttribute(Entity.Attributes.HAS_SPEC, false), "no visible spec");

        assertTrue(model.diagnostics().isEmpty(), "legitimate merges are not collisions");
    }

    @Test
    void identifiersAreIndependentOfTheAbsoluteAnalysisDirectory(@TempDir Path a, @TempDir Path b) throws IOException {
        writeAdaFixture(a);
        writeAdaFixture(b);

        List<String> idsA = codeEntityIds(a);
        List<String> idsB = codeEntityIds(b);
        assertEquals(idsA, idsB,
                "the same source in a different absolute directory must produce the same ids");
        assertTrue(idsA.contains("ada:package:Navigation"));
    }

    private List<String> codeEntityIds(Path repo) {
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults())
                .model().entities().stream()
                .filter(e -> e.kind() != EntityKind.PROJECT) // PROJECT id is the folder name (a label)
                .map(Entity::id).sorted().collect(Collectors.toList());
    }

    private static Entity byId(SoftwareModel model, String id) {
        return model.entity(id).orElseThrow(() -> new AssertionError("missing entity: " + id));
    }

    private static boolean hasEntity(SoftwareModel model, String id) {
        return model.entity(id).isPresent();
    }
}
