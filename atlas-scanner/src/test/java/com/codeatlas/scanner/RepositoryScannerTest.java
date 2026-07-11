package com.codeatlas.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryScannerTest {

    @Test
    void detectsLanguagesAndAppliesExclusions(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("Main.java"), "class Main {}");
        Files.createDirectories(root.resolve("pkg"));
        Files.writeString(root.resolve("pkg/util.adb"), "package body Util is end Util;");
        Files.writeString(root.resolve("app.yaml"), "server: 8080");

        // Excluded directory content must not appear.
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/Generated.java"), "class Generated {}");

        ScanResult result = new RepositoryScanner().scan(root, ScanOptions.defaults());

        assertEquals(3, result.fileCount(), "excluded 'target' must be skipped");
        Map<String, Integer> byLang = result.filesByLanguage();
        assertEquals(1, byLang.get("java"));
        assertEquals(1, byLang.get("ada"));
        assertEquals(1, byLang.get("yaml"));

        assertTrue(result.files().stream().allMatch(f -> f.contentHash().length() == 64),
                "SHA-256 hash should be 64 hex chars");
        assertFalse(result.files().stream()
                .anyMatch(f -> f.relativePath().contains("target")));
    }

    @Test
    void identicalContentProducesIdenticalHash(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("A.java"), "class A {}");
        Files.writeString(root.resolve("B.java"), "class A {}");

        ScanResult result = new RepositoryScanner().scan(root, ScanOptions.defaults());
        var hashes = result.files().stream().map(ScannedFile::contentHash).distinct().toList();
        assertEquals(1, hashes.size(), "same bytes -> same hash");
    }
}
