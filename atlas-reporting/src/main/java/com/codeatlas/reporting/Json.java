package com.codeatlas.reporting;

/**
 * A minimal, dependency-free JSON string escaper. The platform avoids third-party
 * JSON libraries so its output stays lean, deterministic and auditable. Public
 * because the agent tool API renders its results with the same escaping.
 */
public final class Json {

    private Json() {
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
        return b.toString();
    }
}
