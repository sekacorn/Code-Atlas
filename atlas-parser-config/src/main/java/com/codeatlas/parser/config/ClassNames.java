package com.codeatlas.parser.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects fully-qualified Java class names embedded in configuration text, so a
 * bean {@code class="com.example.Service"} or {@code handler=com.example.Job}
 * becomes a resolvable code reference.
 *
 * <p>Conservative by design to avoid noise: a candidate must be dotted, its final
 * segment must start uppercase (a class), and at least one earlier segment must
 * start lowercase (a package). This rejects property paths
 * ({@code logging.level.root}), Maven coordinates ({@code org.apache.commons}),
 * and constants ({@code MyClass.CONSTANT}).
 */
public final class ClassNames {

    private static final Pattern CANDIDATE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)+");

    private ClassNames() {
    }

    public static boolean looksLikeClassName(String token) {
        if (token == null) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        String last = parts[parts.length - 1];
        if (last.isEmpty() || !Character.isUpperCase(last.charAt(0))) {
            return false;
        }
        boolean hasPackageSegment = false;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty() && Character.isLowerCase(parts[i].charAt(0))) {
                hasPackageSegment = true;
                break;
            }
        }
        return hasPackageSegment;
    }

    /** The first class-name-looking token in {@code text}, or {@code null}. */
    public static String firstClassName(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            String candidate = m.group();
            if (looksLikeClassName(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
