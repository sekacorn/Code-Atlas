package com.codeatlas.parser.config;

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

class ConfigParserTest {

    private final ConfigParser parser = new ConfigParser();

    private ParseResult parse(String name, String content) {
        return parser.parse(new ParseRequest(name, Path.of(name), content, "h", "config"));
    }

    private List<Relationship> configures(ParseResult r) {
        return r.relationships().stream()
                .filter(x -> x.kind() == RelationshipKind.CONFIGURES).toList();
    }

    @Test
    void springXmlBeanClassesBecomeReferences() {
        ParseResult r = parse("beans.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <beans>
                    <bean id="svc" class="com.example.CustomerService"/>
                    <bean id="repo" class="com.example.CustomerRepository">
                        <property name="url" value="jdbc:h2:mem:test"/>
                    </bean>
                </beans>
                """);
        List<Relationship> refs = configures(r);
        assertEquals(2, refs.size(), "two bean classes referenced: " + refs);
        assertTrue(refs.stream().anyMatch(x -> x.toId().equals("com.example.CustomerService")));
        assertTrue(refs.stream().allMatch(x -> x.location().isPresent()
                && x.location().get().startLine() > 0), "XML references carry line numbers");
        // The jdbc URL value is not a class name -> not a reference.
        assertTrue(refs.stream().noneMatch(x -> x.toId().contains("jdbc")));

        Entity config = r.entities().stream().filter(e -> e.kind() == EntityKind.CONFIGURATION)
                .findFirst().orElseThrow();
        assertEquals("config:beans.xml", config.id());
    }

    @Test
    void propertiesClassValuesBecomeReferencesAndSecretsAreMasked() {
        ParseResult r = parse("application.properties", """
                app.handler.class=com.example.JobHandler
                logging.level.root=INFO
                spring.datasource.url=jdbc:postgresql://db/app
                spring.datasource.password=s3cr3t
                api.token=abcdef
                """);
        List<Relationship> refs = configures(r);
        assertEquals(1, refs.size(), "only the class-valued property is a reference: " + refs);
        assertEquals("com.example.JobHandler", refs.get(0).toId());
        assertEquals("app.handler.class", refs.get(0).attributes().get("configKey"));

        Entity config = r.entities().stream().filter(e -> e.kind() == EntityKind.CONFIGURATION)
                .findFirst().orElseThrow();
        assertEquals("2", config.attribute("secretKeysMasked").orElse("0"),
                "password and token keys are masked");
        // No secret value is stored anywhere in the result.
        assertFalse(r.toString().contains("s3cr3t"));
        assertTrue(r.relationships().stream().flatMap(x -> x.attributes().values().stream())
                .noneMatch(v -> v.contains("s3cr3t") || v.contains("abcdef")));
    }

    @Test
    void yamlNestedKeysResolveToDottedPaths() {
        ParseResult r = parse("application.yml", """
                app:
                  mapper:
                    impl: com.example.MapperImpl   # a class reference
                  name: my-app
                security:
                  secret-key: topsecret
                """);
        List<Relationship> refs = configures(r);
        assertEquals(1, refs.size());
        assertEquals("com.example.MapperImpl", refs.get(0).toId());
        assertEquals("app.mapper.impl", refs.get(0).attributes().get("configKey"),
                "nested YAML key path is tracked");
    }

    @Test
    void propertyPathsAndMavenCoordinatesAreNotMistakenForClasses() {
        ParseResult r = parse("misc.properties", """
                logging.level.org.springframework=DEBUG
                build.group=org.apache.commons
                feature.enabled=true
                """);
        assertTrue(configures(r).isEmpty(), "no lowercase-tailed dotted value is a class");
    }

    @Test
    void malformedXmlIsReportedNotThrown() {
        ParseResult r = parse("broken.xml", "<beans><bean class=\"com.x.A\"></beans>");
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("XML")),
                "a parse problem is surfaced as an issue");
        // A CONFIGURATION entity is still produced.
        assertTrue(r.entities().stream().anyMatch(e -> e.kind() == EntityKind.CONFIGURATION));
    }

    @Test
    void xmlRejectsExternalEntities() {
        ParseResult r = parse("beans.xml", """
                <!DOCTYPE beans [<!ENTITY external SYSTEM "file:///does-not-exist">]>
                <beans><bean class="&external;"/></beans>
                """);
        assertTrue(r.issues().stream().anyMatch(i -> i.message().contains("XML")));
        assertTrue(configures(r).isEmpty(), "external entity content must never become a reference");
    }

    @Test
    void buildDescriptorsAreLeftToTheBuildParser() {
        // The pipeline gives each file to exactly one parser, so ownership must not
        // depend on classpath order: build files belong to atlas-parser-build, which
        // extracts modules, dependencies and declared mains from them.
        assertFalse(parser.supports(request("pom.xml")), "pom.xml is a build descriptor");
        assertFalse(parser.supports(request("app/build.gradle")));
        assertFalse(parser.supports(request("settings.gradle.kts")));
        assertFalse(parser.supports(request("ada/mission.gpr")));
        // Ordinary configuration is still claimed.
        assertTrue(parser.supports(request("beans.xml")));
        assertTrue(parser.supports(request("application.properties")));
        assertTrue(parser.supports(request("config/app.yml")));
    }

    private ParseRequest request(String name) {
        return new ParseRequest(name, Path.of(name), "", "h", "config");
    }
}
