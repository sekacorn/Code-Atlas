package com.codeatlas.parser.java;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLanguageParserTest {

    private final JavaLanguageParser parser = new JavaLanguageParser();

    private ParseResult parse(String content) {
        return parser.parse(new ParseRequest("src/Sample.java",
                Path.of("src/Sample.java"), content, "hash", "java"));
    }

    @Test
    void extractsTypesMethodsFieldsAndContainment() {
        String src = """
                package com.example;

                public class Service {
                    private int count;

                    public int compute(int x) {
                        if (x > 0) {
                            return x + count;
                        }
                        return count;
                    }

                    private void helper() {
                        compute(1);
                    }
                }
                """;
        ParseResult result = parse(src);

        List<Entity> classes = result.entities().stream()
                .filter(e -> e.kind() == EntityKind.CLASS).toList();
        assertEquals(1, classes.size());
        assertEquals("com.example.Service", classes.get(0).qualifiedName());

        Entity compute = result.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals("compute"))
                .findFirst().orElseThrow();
        // base 1 + one if = 2
        assertEquals(2, compute.intAttribute(Entity.Attributes.CYCLOMATIC_COMPLEXITY, -1));
        assertTrue(compute.boolAttribute(Entity.Attributes.EXTERNALLY_EXPOSED, false));

        Entity helper = result.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals("helper"))
                .findFirst().orElseThrow();
        assertEquals("private", helper.attribute(Entity.Attributes.VISIBILITY).orElse(""));

        boolean hasField = result.entities().stream()
                .anyMatch(e -> e.kind() == EntityKind.FIELD && e.name().equals("count"));
        assertTrue(hasField);
    }

    @Test
    void emitsUnresolvedCallEdges() {
        String src = """
                package com.example;
                class A {
                    void run() { doWork(); doWork(); }
                    void doWork() {}
                }
                """;
        ParseResult result = parse(src);
        List<Relationship> calls = result.relationships().stream()
                .filter(r -> r.kind() == RelationshipKind.CALLS).toList();
        assertEquals(2, calls.size());
        assertTrue(calls.stream().noneMatch(Relationship::resolved), "cross-ref resolution is deferred to the Linker");
        assertEquals("doWork", calls.get(0).attributes().get("callName"));
    }

    @Test
    void recordsSyntaxErrorsWithoutThrowing() {
        ParseResult result = parse("class Broken { void x( }");
        assertTrue(result.hasErrors());
    }

    @Test
    void marksNativeMethodsAsAJniBoundary() {
        ParseResult result = parse("""
                class Bridge {
                    native long compute(long h);
                    void ordinary() {}
                }
                """);
        Entity nativeMethod = result.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals("compute"))
                .findFirst().orElseThrow();
        assertTrue(nativeMethod.boolAttribute(Entity.Attributes.NATIVE_METHOD, false),
                "a native method carries the JNI-boundary marker");
        Entity ordinary = result.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals("ordinary"))
                .findFirst().orElseThrow();
        assertTrue(!ordinary.boolAttribute(Entity.Attributes.NATIVE_METHOD, false),
                "a non-native method has no marker");
    }
}
