package com.codeatlas.parser.build;

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

/** Extraction of modules, dependencies and declared mains from Maven, Gradle and GNAT files. */
class BuildParserTest {

    private final BuildParser parser = new BuildParser();

    private ParseResult parse(String name, String content) {
        return parser.parse(new ParseRequest(name, Path.of(name), content, "h", "build"));
    }

    private Entity module(ParseResult r) {
        return r.entities().stream().filter(e -> e.kind() == EntityKind.MODULE).findFirst().orElseThrow();
    }

    private List<Relationship> of(ParseResult r, RelationshipKind kind) {
        return r.relationships().stream().filter(x -> x.kind() == kind).toList();
    }

    // ---- ownership ----

    @Test
    void claimsOnlyBuildFiles() {
        assertTrue(parser.supports(req("pom.xml")));
        assertTrue(parser.supports(req("app/build.gradle")));
        assertTrue(parser.supports(req("settings.gradle.kts")));
        assertTrue(parser.supports(req("ada/mission.gpr")));
        assertFalse(parser.supports(req("beans.xml")), "an ordinary XML config is not a build file");
        assertFalse(parser.supports(req("application.properties")));
        assertFalse(parser.supports(req("Main.java")));
    }

    private ParseRequest req(String name) {
        return new ParseRequest(name, Path.of(name), "", "h", "build");
    }

    // ---- Maven ----

    @Test
    void mavenPomBecomesAModuleWithCoordinatesAndDependencies() {
        ParseResult r = parse("app/pom.xml", """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>mission-app</artifactId>
                    <version>1.2.0</version>
                    <packaging>jar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>mission-core</artifactId>
                            <version>1.2.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.16</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Entity m = module(r);
        assertEquals("build:module:com.example:mission-app", m.id());
        assertEquals("mission-app", m.name());
        assertEquals("com.example", m.attribute("groupId").orElseThrow());
        assertEquals("1.2.0", m.attribute("moduleVersion").orElseThrow());
        assertEquals("maven", m.attribute("buildSystem").orElseThrow());
        assertEquals("app", m.attribute("moduleDir").orElseThrow());

        List<Relationship> deps = of(r, RelationshipKind.DEPENDS_ON);
        assertEquals(2, deps.size());
        assertTrue(deps.stream().noneMatch(Relationship::resolved),
                "coordinates resolve later, in the Linker");
        assertTrue(deps.stream().anyMatch(d -> d.attributes().get("typeName").equals("com.example:mission-core")));
        assertTrue(deps.stream().anyMatch(d -> d.attributes().get("typeName").equals("org.slf4j:slf4j-api")
                && d.attributes().get("scope").equals("test")));
    }

    @Test
    void mavenModuleInheritsCoordinatesFromParentWhenOmitted() {
        ParseResult r = parse("pom.xml", """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>3.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);
        Entity m = module(r);
        assertEquals("build:module:com.example:child", m.id());
        assertEquals("3.0.0", m.attribute("moduleVersion").orElseThrow());
        assertEquals("", m.attribute("moduleDir").orElseThrow(), "a root pom's module dir is the repo root");
    }

    @Test
    void mavenDependencyBlockDoesNotLeakIntoTheModulesOwnCoordinates() {
        // A naive scanner would read the dependency's groupId as the module's.
        ParseResult r = parse("pom.xml", """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>app</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.other</groupId>
                            <artifactId>lib</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertEquals("build:module:com.example:app", module(r).id());
    }

    // ---- Gradle ----

    @Test
    void gradleScriptBecomesAModuleWithCoordinateAndProjectDependencies() {
        ParseResult r = parse("app/build.gradle", """
                plugins { id 'java' }
                group = 'com.example'
                version = '2.0.0'
                dependencies {
                    implementation project(':core')
                    implementation 'org.slf4j:slf4j-api:2.0.16'
                    testImplementation "org.junit.jupiter:junit-jupiter:5.11.4"
                }
                """);
        Entity m = module(r);
        assertEquals("com.example:app", m.qualifiedName(), "module name defaults to its directory");
        assertEquals("gradle", m.attribute("buildSystem").orElseThrow());
        assertEquals("2.0.0", m.attribute("moduleVersion").orElseThrow());

        List<Relationship> deps = of(r, RelationshipKind.DEPENDS_ON);
        assertEquals(3, deps.size());
        assertTrue(deps.stream().anyMatch(d -> d.attributes().get("typeName").equals("core")),
                "project(':core') references a sibling module by name");
        assertTrue(deps.stream().anyMatch(d -> d.attributes().get("typeName").equals("org.slf4j:slf4j-api")),
                "a coordinate is identified without its version");
        assertTrue(deps.stream().anyMatch(d ->
                d.attributes().get("typeName").equals("org.junit.jupiter:junit-jupiter")));
    }

    @Test
    void gradleSettingsNameTheRootProjectAndItsIncludes() {
        ParseResult r = parse("settings.gradle", """
                rootProject.name = 'mission'
                include 'core', 'app'
                """);
        Entity m = module(r);
        assertEquals("mission", m.name());
        assertEquals("core,app", m.attribute("includes").orElseThrow());
    }

    @Test
    void gradleCommentsAreIgnored() {
        ParseResult r = parse("build.gradle", """
                // implementation 'commented:out:1.0'
                dependencies {
                    implementation 'real:dep:1.0'
                }
                """);
        List<Relationship> deps = of(r, RelationshipKind.DEPENDS_ON);
        assertEquals(1, deps.size());
        assertEquals("real:dep", deps.get(0).attributes().get("typeName"));
    }

    // ---- GNAT ----

    @Test
    void gnatProjectDeclaresItsMainUnitsAndDependencies() {
        ParseResult r = parse("mission.gpr", """
                with "core.gpr";
                project Mission is
                   for Source_Dirs use ("src", "ada");
                   for Object_Dir use "obj";
                   for Main use ("mission_main.adb", "tool_main.adb");
                end Mission;
                """);
        Entity m = module(r);
        assertEquals("build:module:Mission", m.id());
        assertEquals("gnat", m.attribute("buildSystem").orElseThrow());
        assertEquals("src,ada", m.attribute("sourceDirs").orElseThrow());
        assertEquals("mission_main,tool_main", m.attribute("mains").orElseThrow());

        List<Relationship> mains = of(r, RelationshipKind.DECLARES_MAIN);
        assertEquals(2, mains.size());
        assertTrue(mains.stream().anyMatch(x -> x.attributes().get("callName").equals("mission_main")),
                "the main unit name (not the file name) is what resolves to a subprogram");
        assertTrue(mains.stream().allMatch(x -> x.location().isPresent()), "declared mains cite their line");
        assertTrue(mains.stream().noneMatch(Relationship::resolved), "the unit resolves later, in the Linker");

        assertTrue(of(r, RelationshipKind.DEPENDS_ON).stream()
                        .anyMatch(d -> d.attributes().get("typeName").equals("core")),
                "a withed project is a module dependency");
    }

    @Test
    void gnatMainListMaySpanLinesAndIgnoresComments() {
        ParseResult r = parse("mission.gpr", """
                project Mission is
                   --  for Main use ("commented_out.adb");
                   for Main use
                     ("first_main.adb",
                      "second_main.adb");
                end Mission;
                """);
        List<Relationship> mains = of(r, RelationshipKind.DECLARES_MAIN);
        assertEquals(2, mains.size());
        assertTrue(mains.stream().anyMatch(x -> x.attributes().get("callName").equals("second_main")));
        assertTrue(mains.stream().noneMatch(x -> x.attributes().get("callName").equals("commented_out")));
    }

    // ---- honesty ----

    @Test
    void malformedBuildFilesAreRecordedNotThrown() {
        ParseResult broken = parse("pom.xml", "<project><groupId>oops");
        assertTrue(broken.hasErrors(), "a malformed pom is reported, never fatal");

        ParseResult noArtifact = parse("pom.xml", "<project><groupId>com.example</groupId></project>");
        assertTrue(noArtifact.entities().stream().noneMatch(e -> e.kind() == EntityKind.MODULE),
                "no artifactId means no module is invented");

        ParseResult noProject = parse("empty.gpr", "--  just a comment\n");
        assertTrue(noProject.entities().stream().noneMatch(e -> e.kind() == EntityKind.MODULE));
    }

    @Test
    void mavenPomRejectsExternalEntities() {
        ParseResult result = parse("pom.xml", """
                <!DOCTYPE project [<!ENTITY external SYSTEM "file:///does-not-exist">]>
                <project><artifactId>&external;</artifactId></project>
                """);
        assertTrue(result.hasErrors(), "DOCTYPE declarations must be rejected before entity expansion");
        assertTrue(result.entities().stream().noneMatch(e -> e.kind() == EntityKind.MODULE));
    }

    @Test
    void everyModuleIsContainedByItsBuildFile() {
        ParseResult r = parse("app/pom.xml", """
                <project><groupId>g</groupId><artifactId>a</artifactId></project>
                """);
        assertTrue(of(r, RelationshipKind.CONTAINS).stream()
                        .anyMatch(c -> c.fromId().equals("file:app/pom.xml")
                                && c.toId().equals("build:module:g:a")),
                "the build file contains the module it declares");
    }
}
