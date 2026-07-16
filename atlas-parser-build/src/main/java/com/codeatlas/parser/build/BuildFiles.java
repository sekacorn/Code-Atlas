package com.codeatlas.parser.build;

import java.util.Locale;

/**
 * Which files this parser owns. Build files are claimed here and deliberately
 * <em>not</em> by the configuration parser: the pipeline gives each file to exactly
 * one parser, so ownership must be explicit rather than depend on classpath order.
 */
public final class BuildFiles {

    /** Maven build descriptor. */
    public static final String MAVEN = "maven";
    /** Gradle build/settings script (Groovy or Kotlin DSL). */
    public static final String GRADLE = "gradle";
    /** GNAT project file. */
    public static final String GNAT = "gnat";

    private BuildFiles() {
    }

    /** The build system a file belongs to, or {@code null} when it is not a build file. */
    public static String systemOf(String relativePath) {
        String name = fileName(relativePath).toLowerCase(Locale.ROOT);
        if (name.equals("pom.xml")) {
            return MAVEN;
        }
        if (name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")) {
            return GRADLE;
        }
        if (name.endsWith(".gpr")) {
            return GNAT;
        }
        return null;
    }

    public static boolean isBuildFile(String relativePath) {
        return systemOf(relativePath) != null;
    }

    public static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** The directory holding a build file, normalized with '/' and "" at the root. */
    public static String directoryOf(String relativePath) {
        String p = relativePath.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash > 0 ? p.substring(0, slash) : "";
    }
}
