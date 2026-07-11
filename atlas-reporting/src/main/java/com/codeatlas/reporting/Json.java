package com.codeatlas.reporting;

/**
 * A minimal, dependency-free JSON writer. The platform avoids third-party JSON
 * libraries here to keep the reporting module lean and its output auditable.
 */
final class Json {

    private final StringBuilder sb = new StringBuilder();

    Json append(String raw) {
        sb.append(raw);
        return this;
    }

    static String quote(String s) {
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

    @Override
    public String toString() {
        return sb.toString();
    }
}
