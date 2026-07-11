package com.codeatlas.core;

import java.util.Map;
import java.util.Set;

/**
 * Deterministic line accounting for a file: total, comment and blank lines.
 *
 * <p>Comment syntax is chosen by language so the counts are meaningful across the
 * mixed-language repositories the platform targets. Block comments ({@code /* ... *}{@code /})
 * are tracked with a small state machine; anything unrecognised is counted as code.
 */
public record LineStats(int total, int comment, int blank) {

    private static final Map<String, String> LINE_COMMENT = Map.ofEntries(
            Map.entry("java", "//"), Map.entry("c", "//"), Map.entry("cpp", "//"),
            Map.entry("ada", "--"), Map.entry("ada-project", "--"), Map.entry("sql", "--"),
            Map.entry("python", "#"), Map.entry("yaml", "#"), Map.entry("properties", "#"),
            Map.entry("toml", "#"), Map.entry("ini", "#"));

    private static final Set<String> BLOCK_COMMENT_LANGS = Set.of("java", "c", "cpp", "sql");

    public static LineStats count(String content, String languageId) {
        String lineComment = LINE_COMMENT.get(languageId);
        boolean blocks = BLOCK_COMMENT_LANGS.contains(languageId);

        String[] lines = content.split("\n", -1);
        // A trailing newline yields a final empty element; ignore it for totals.
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) {
            limit--;
        }

        int total = 0;
        int comment = 0;
        int blank = 0;
        boolean inBlock = false;

        for (int i = 0; i < limit; i++) {
            total++;
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                blank++;
                continue;
            }
            if (inBlock) {
                comment++;
                if (trimmed.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (blocks && trimmed.startsWith("/*")) {
                comment++;
                if (!trimmed.contains("*/")) {
                    inBlock = true;
                }
                continue;
            }
            if (lineComment != null && trimmed.startsWith(lineComment)) {
                comment++;
            }
        }
        return new LineStats(total, comment, blank);
    }
}
