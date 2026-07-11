package com.codeatlas.scanner;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a file to a language id and a coarse {@link FileCategory} using its
 * extension (and a few well-known filenames).
 *
 * <p>Detection is deliberately conservative and offline &mdash; no content
 * sniffing beyond extension for now. The table covers the milestone-1 languages
 * (Java, Ada) plus a broad set of formats the later parser modules will claim, so
 * the dashboard reports an honest language distribution even before every parser
 * exists.
 */
public final class LanguageDetector {

    // extension (lower, no dot) -> language id
    private static final Map<String, String> EXTENSION_LANGUAGE = Map.ofEntries(
            // First-class languages
            Map.entry("java", "java"),
            Map.entry("ads", "ada"),
            Map.entry("adb", "ada"),
            Map.entry("ada", "ada"),
            Map.entry("gpr", "ada-project"),
            // Configuration
            Map.entry("xml", "xml"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("json", "json"),
            Map.entry("properties", "properties"),
            Map.entry("toml", "toml"),
            Map.entry("ini", "ini"),
            // Database
            Map.entry("sql", "sql"),
            Map.entry("ddl", "sql"),
            // Future languages (detected, parsed later)
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("py", "python"),
            Map.entry("cob", "cobol"),
            Map.entry("cbl", "cobol"),
            Map.entry("f", "fortran"),
            Map.entry("f90", "fortran"),
            // Docs
            Map.entry("md", "markdown"),
            Map.entry("txt", "text"),
            Map.entry("adoc", "asciidoc")
    );

    private static final Map<String, FileCategory> LANGUAGE_CATEGORY = Map.ofEntries(
            Map.entry("java", FileCategory.SOURCE),
            Map.entry("ada", FileCategory.SOURCE),
            Map.entry("c", FileCategory.SOURCE),
            Map.entry("cpp", FileCategory.SOURCE),
            Map.entry("python", FileCategory.SOURCE),
            Map.entry("cobol", FileCategory.SOURCE),
            Map.entry("fortran", FileCategory.SOURCE),
            Map.entry("ada-project", FileCategory.BUILD),
            Map.entry("xml", FileCategory.CONFIG),
            Map.entry("yaml", FileCategory.CONFIG),
            Map.entry("json", FileCategory.CONFIG),
            Map.entry("properties", FileCategory.CONFIG),
            Map.entry("toml", FileCategory.CONFIG),
            Map.entry("ini", FileCategory.CONFIG),
            Map.entry("sql", FileCategory.DATABASE),
            Map.entry("markdown", FileCategory.DOCUMENTATION),
            Map.entry("asciidoc", FileCategory.DOCUMENTATION),
            Map.entry("text", FileCategory.DOCUMENTATION)
    );

    // Well-known build filenames (no useful extension).
    private static final Map<String, String> FILENAME_LANGUAGE = Map.of(
            "pom.xml", "xml",
            "makefile", "make",
            "dockerfile", "docker"
    );

    public record Detection(String languageId, FileCategory category) {
    }

    /** Detects language and category for a file name. Never returns null. */
    public Detection detect(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);

        String byName = FILENAME_LANGUAGE.get(lower);
        if (byName != null) {
            return new Detection(byName, LANGUAGE_CATEGORY.getOrDefault(byName, FileCategory.BUILD));
        }

        String ext = extensionOf(lower);
        String lang = EXTENSION_LANGUAGE.get(ext);
        if (lang == null) {
            return new Detection("unknown", FileCategory.OTHER);
        }
        return new Detection(lang, LANGUAGE_CATEGORY.getOrDefault(lang, FileCategory.OTHER));
    }

    private static String extensionOf(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        return dot > 0 && dot < lowerName.length() - 1 ? lowerName.substring(dot + 1) : "";
    }
}
