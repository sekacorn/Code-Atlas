package com.codeatlas.parser.build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Gradle build/settings scripts with a deterministic line scanner — not a
 * Groovy/Kotlin interpreter. Nothing is executed: only literal, statically written
 * declarations are recorded.
 *
 * <p>Limits (honest by design): dynamic constructs are invisible — coordinates built
 * from variables, version catalogs ({@code libs.foo}), {@code ext} properties,
 * plugin-injected dependencies and conditional blocks are not resolved. Only literal
 * string coordinates and {@code project(':x')} references are extracted.
 */
final class GradleExtractor {

    // group = 'com.example'   |   group "com.example"
    private static final Pattern GROUP =
            Pattern.compile("^\\s*group\\s*[=(]?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern VERSION =
            Pattern.compile("^\\s*version\\s*[=(]?\\s*[\"']([^\"']+)[\"']");
    // rootProject.name = 'mission'
    private static final Pattern ROOT_NAME =
            Pattern.compile("^\\s*rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    // include 'core', 'app'   |   include(":core")
    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*include\\s*\\(?\\s*(.+)$");
    // implementation 'g:a:v'  |  testImplementation("g:a:v")  |  api 'g:a'
    private static final Pattern DEP_COORD = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly"
                    + "|testRuntimeOnly|annotationProcessor|kapt)\\s*\\(?\\s*[\"']([^\"':]+:[^\"']+)[\"']");
    // implementation project(':core')
    private static final Pattern DEP_PROJECT = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testCompileOnly"
                    + "|testRuntimeOnly)\\s*\\(?\\s*project\\s*\\(\\s*[\"':]*([^\"')]+)[\"']?\\s*\\)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private GradleExtractor() {
    }

    /** One declared Gradle dependency: either a coordinate or a project reference. */
    record GradleDep(String target, String configuration, boolean projectReference, int line) {
    }

    /** What a Gradle script declares. */
    record GradleBuild(String rootName, String group, String version,
                       List<GradleDep> dependencies, Set<String> includes) {
    }

    static GradleBuild parse(String content) {
        String rootName = null;
        String group = null;
        String version = null;
        List<GradleDep> deps = new ArrayList<>();
        Set<String> includes = new LinkedHashSet<>();

        String[] lines = content.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = i + 1;

            Matcher m;
            if (rootName == null && (m = ROOT_NAME.matcher(line)).find()) {
                rootName = m.group(1);
                continue;
            }
            if (group == null && (m = GROUP.matcher(line)).find()) {
                group = m.group(1);
                continue;
            }
            if (version == null && (m = VERSION.matcher(line)).find()) {
                version = m.group(1);
                continue;
            }
            if ((m = DEP_PROJECT.matcher(line)).find()) {
                String target = m.group(2).replace(":", "").trim();
                if (!target.isEmpty()) {
                    deps.add(new GradleDep(target, m.group(1), true, lineNo));
                }
                continue;
            }
            if ((m = DEP_COORD.matcher(line)).find()) {
                deps.add(new GradleDep(coordinateKey(m.group(2)), m.group(1), false, lineNo));
                continue;
            }
            if ((m = INCLUDE.matcher(line)).find()) {
                Matcher q = QUOTED.matcher(m.group(1));
                while (q.find()) {
                    String inc = q.group(1).replace(":", "/").trim();
                    if (!inc.isEmpty()) {
                        includes.add(inc);
                    }
                }
            }
        }
        return new GradleBuild(rootName, group, version, List.copyOf(deps), includes);
    }

    /** "g:a:v" -> "g:a" (the identity of the dependency, without its version). */
    private static String coordinateKey(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : coordinate;
    }

    private static String stripComment(String line) {
        int slash = line.indexOf("//");
        return slash >= 0 ? line.substring(0, slash) : line;
    }
}
