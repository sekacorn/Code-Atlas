package com.codeatlas.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the stable-identifier grammar and the merge/collision rules that Ada
 * spec/body unification depends on.
 */
class EntityStableIdTest {

    @Test
    void grammarMatchesTheDocumentedScheme() {
        assertEquals("java:type:com.example.CustomerService",
                Entity.stableId("java", EntityKind.CLASS, "com.example.CustomerService"));
        assertEquals("java:method:com.example.CustomerService#findCustomer(java.lang.String)",
                Entity.stableId("java", EntityKind.METHOD, "com.example.CustomerService#findCustomer(java.lang.String)"));
        assertEquals("java:field:com.example.CustomerService#repository",
                Entity.stableId("java", EntityKind.FIELD, "com.example.CustomerService#repository"));
        assertEquals("java:constructor:com.example.CustomerService#CustomerService()",
                Entity.stableId("java", EntityKind.CONSTRUCTOR, "com.example.CustomerService#CustomerService()"));
        assertEquals("ada:package:Navigation",
                Entity.stableId("ada", EntityKind.PACKAGE, "Navigation"));
        assertEquals("ada:procedure:Navigation.Calculate_Route",
                Entity.stableId("ada", EntityKind.PROCEDURE, "Navigation.Calculate_Route"));
        assertEquals("ada:function:Navigation.Find_Path(Integer)",
                Entity.stableId("ada", EntityKind.FUNCTION, "Navigation.Find_Path(Integer)"));
        assertEquals("ada:type:Navigation.Route_Record",
                Entity.stableId("ada", EntityKind.TYPE, "Navigation.Route_Record"));
        assertEquals("file:src/main/java/com/example/CustomerService.java",
                Entity.stableId("java", EntityKind.FILE, "src/main/java/com/example/CustomerService.java"));
        assertEquals("project:my-repo",
                Entity.stableId("n/a", EntityKind.PROJECT, "my-repo"));
    }

    @Test
    void identityDoesNotDependOnLocation() {
        Entity a = Entity.builder(EntityKind.METHOD, "run").qualifiedName("com.x.A#run()")
                .language("java").location(SourceLocation.of("A.java", 5, 8)).build();
        Entity b = Entity.builder(EntityKind.METHOD, "run").qualifiedName("com.x.A#run()")
                .language("java").location(SourceLocation.of("A.java", 40, 43)).build();
        assertEquals(a.id(), b.id(), "moving lines must not change identity");
        assertEquals(a.id(), a.stableId());
    }

    @Test
    void mergeCombinesExposureComplexityAndSpecBodyEvidence() {
        Entity spec = Entity.builder(EntityKind.PROCEDURE, "Reset").qualifiedName("Nav.Reset")
                .language("ada").location(SourceLocation.of("nav.ads", 3, 3))
                .attribute(Entity.Attributes.ADA_PART, "spec")
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true).build();
        Entity body = Entity.builder(EntityKind.PROCEDURE, "Reset").qualifiedName("Nav.Reset")
                .language("ada").location(SourceLocation.of("nav.adb", 10, 14))
                .attribute(Entity.Attributes.ADA_PART, "body")
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, false)
                .attribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, 5).build();

        assertEquals(spec.id(), body.id(), "spec and body share one identity");
        Entity merged = Entity.merge(spec, body);
        assertTrue(merged.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false), "exposure is OR-ed");
        assertEquals(5, merged.intAttribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, 0));
        assertTrue(merged.boolAttribute(Entity.Attributes.HAS_SPEC, false));
        assertTrue(merged.boolAttribute(Entity.Attributes.HAS_BODY, false));
        assertTrue(merged.attribute(Entity.Attributes.SPEC_LOCATION).orElse("").contains("nav.ads"));
        assertTrue(merged.attribute(Entity.Attributes.BODY_LOCATION).orElse("").contains("nav.adb"));
    }

    @Test
    void modelMergesSpecAndBodyButReportsGenuineCollisions() {
        SoftwareModel model = new SoftwareModel();
        model.addEntity(Entity.builder(EntityKind.PROCEDURE, "Reset").qualifiedName("Nav.Reset")
                .language("ada").location(SourceLocation.of("nav.ads", 3, 3))
                .attribute(Entity.Attributes.ADA_PART, "spec").build());
        model.addEntity(Entity.builder(EntityKind.PROCEDURE, "Reset").qualifiedName("Nav.Reset")
                .language("ada").location(SourceLocation.of("nav.adb", 9, 12))
                .attribute(Entity.Attributes.ADA_PART, "body").build());
        assertEquals(1, model.entityCount(), "spec + body collapse to one entity");
        assertTrue(model.diagnostics().isEmpty(), "a legitimate spec/body merge is not a collision");

        // Two genuinely different declarations that happen to share an id -> collision.
        SoftwareModel clash = new SoftwareModel();
        clash.addEntity(Entity.builder(EntityKind.METHOD, "m").qualifiedName("com.x.A#m()")
                .language("java").location(SourceLocation.of("A.java", 1, 2)).build());
        clash.addEntity(Entity.builder(EntityKind.METHOD, "m").qualifiedName("com.x.A#m()")
                .language("java").location(SourceLocation.of("Other.java", 50, 51)).build());
        assertEquals(1, clash.entityCount(), "the first declaration is kept, not overwritten");
        assertEquals(1, clash.diagnostics().size());
        assertEquals(Diagnostic.STABLE_ID_COLLISION, clash.diagnostics().get(0).code());
    }
}
