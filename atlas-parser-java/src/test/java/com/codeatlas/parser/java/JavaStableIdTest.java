package com.codeatlas.parser.java;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves Java stable identifiers survive edits that move an entity within its file.
 */
class JavaStableIdTest {

    private final JavaLanguageParser parser = new JavaLanguageParser();

    private String idOfCompute(String src) {
        ParseResult r = parser.parse(new ParseRequest("src/S.java", Path.of("src/S.java"), src, "h", "java"));
        return r.entities().stream()
                .filter(e -> e.kind() == EntityKind.METHOD && e.name().equals("compute"))
                .map(Entity::id).findFirst().orElseThrow();
    }

    @Test
    void idHasSemanticFormAndSurvivesLineMovement() {
        String base = """
                package com.example;
                public class S {
                    public int compute(String a) { return a.length(); }
                }
                """;
        String withBlankLinesAndComments = """
                package com.example;


                // a leading comment
                /* and a block comment
                   spanning lines */
                public class S {
                    // an unrelated helper added above
                    private void other() { }

                    public int compute(String a) { return a.length(); }
                }
                """;

        String id = idOfCompute(base);
        assertEquals("java:method:com.example.S#compute(String)", id, "id is semantic, not positional");
        assertEquals(id, idOfCompute(withBlankLinesAndComments),
                "blank lines, comments and unrelated methods must not change the id");
    }

    @Test
    void rescanningIdenticalSourceYieldsIdenticalId() {
        String src = "package p; class C { void run(){} }";
        String a = parser.parse(new ParseRequest("p/C.java", Path.of("p/C.java"), src, "h", "java"))
                .entities().stream().filter(e -> e.name().equals("run")).map(Entity::id).findFirst().orElseThrow();
        String b = parser.parse(new ParseRequest("p/C.java", Path.of("p/C.java"), src, "h", "java"))
                .entities().stream().filter(e -> e.name().equals("run")).map(Entity::id).findFirst().orElseThrow();
        assertEquals(a, b);
        assertTrue(a.startsWith("java:method:"), a);
    }
}
