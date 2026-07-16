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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build files end to end: modules are discovered, every file is assigned to the
 * module that owns it, in-repository dependencies link module→module while
 * third-party ones stay honestly unresolved, and a build-declared main resolves to
 * its subprogram and is therefore never dead code.
 */
class BuildMembershipTest {

    /** A two-module Maven build plus a GNAT project that declares an Ada main. */
    private static void writeFixture(Path repo) throws IOException {
        Files.createDirectories(repo.resolve("core/src"));
        Files.createDirectories(repo.resolve("app/src"));
        Files.createDirectories(repo.resolve("ada"));

        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>mission-root</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.writeString(repo.resolve("core/pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>mission-core</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.writeString(repo.resolve("app/pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>mission-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>mission-core</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>2.0.16</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(repo.resolve("core/src/Engine.java"),
                "package com.example.core;\npublic class Engine { public void run() {} }\n");
        Files.writeString(repo.resolve("app/src/App.java"),
                "package com.example.app;\npublic class App { public static void main(String[] a) {} }\n");

        Files.writeString(repo.resolve("ada/mission.gpr"), """
                project Mission is
                   for Source_Dirs use (".");
                   for Main use ("mission_main.adb");
                end Mission;
                """);
        Files.writeString(repo.resolve("ada/mission_main.adb"), """
                procedure Mission_Main is
                begin
                   null;
                end Mission_Main;
                """);
    }

    private SoftwareModel scan(Path repo) throws IOException {
        writeFixture(repo);
        return CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults()).model();
    }

    @Test
    void everyBuildFileBecomesAModule(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        List<String> modules = model.entitiesOfKind(EntityKind.MODULE).stream()
                .map(Entity::id).sorted().toList();
        assertEquals(List.of("build:module:Mission",
                "build:module:com.example:mission-app",
                "build:module:com.example:mission-core",
                "build:module:com.example:mission-root"), modules);
    }

    @Test
    void filesBelongToTheDeepestModuleThatContainsThem(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        // app/src/App.java sits inside both the root module's dir and app's: the
        // nearest module wins, otherwise multi-module membership would be meaningless.
        assertEquals("build:module:com.example:mission-app", ownerOf(model, "file:app/src/App.java"));
        assertEquals("build:module:com.example:mission-core", ownerOf(model, "file:core/src/Engine.java"));
        assertEquals("build:module:Mission", ownerOf(model, "file:ada/mission_main.adb"));
        // The root pom itself is only covered by the root module.
        assertEquals("build:module:com.example:mission-root", ownerOf(model, "file:pom.xml"));
    }

    @Test
    void inRepositoryDependenciesLinkWhileThirdPartyOnesStayUnresolved(@TempDir Path repo) throws IOException {
        SoftwareModel model = scan(repo);
        String app = "build:module:com.example:mission-app";
        assertTrue(model.outgoing(app).stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.DEPENDS_ON && r.resolved()
                                && r.toId().equals("build:module:com.example:mission-core")),
                "a dependency on a module in this repository resolves to it");
        assertTrue(model.outgoing(app).stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.DEPENDS_ON && !r.resolved()
                                && "org.slf4j:slf4j-api".equals(r.attributes().get("typeName"))),
                "a third-party coordinate stays unresolved - an honest external dependency");
        assertTrue(model.entities().stream().noneMatch(e -> e.id().contains("slf4j")),
                "no node is fabricated for a dependency outside the repository");
    }

    @Test
    void buildDeclaredMainResolvesToItsSubprogramAndIsNotDeadCode(@TempDir Path repo) throws IOException {
        writeFixture(repo);
        var result = CodeAtlasPipeline.withDiscoveredParsers().run(repo, PipelineConfig.defaults());
        SoftwareModel model = result.model();

        String main = "ada:procedure:Mission_Main";
        assertTrue(model.entity(main).isPresent(), "the Ada main procedure was parsed");
        assertTrue(model.incoming(main).stream()
                        .anyMatch(r -> r.kind() == RelationshipKind.DECLARES_MAIN && r.resolved()
                                && r.fromId().equals("build:module:Mission")),
                "the GNAT project's declared main resolves to the subprogram that defines it");

        // Nothing in the source calls Mission_Main; only the build declares it. Without
        // build parsing it would look dead, which is exactly the false positive this
        // milestone removes.
        assertFalse(result.analysis().deadCode().stream().anyMatch(d -> d.stableId().equals(main)),
                "a build-declared main is an entry point, never dead code");
    }

    private static String ownerOf(SoftwareModel model, String fileId) {
        return model.incoming(fileId).stream()
                .filter(r -> r.kind() == RelationshipKind.CONTAINS)
                .filter(r -> model.entity(r.fromId()).map(e -> e.kind() == EntityKind.MODULE).orElse(false))
                .map(Relationship::fromId)
                .findFirst().orElse("(unowned)");
    }
}
