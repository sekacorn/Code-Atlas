package com.codeatlas.core;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.RepositoryParser;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserFuzzTest {

    private static final String[] FILES = {
            "Input.java", "input.adb", "input.ads", "schema.sql", "pom.xml",
            "build.gradle", "project.gpr", "application.properties", "config.yml"
    };

    private static final ParserRegistry PARSERS = ParserRegistry.discover();

    @MethodSource("seeds")
    @FuzzTest(maxDuration = "30s")
    void malformedInputNeverEscapesAParser(byte[] bytes) {
        int selector = bytes.length == 0 ? 0 : Byte.toUnsignedInt(bytes[0]);
        String file = FILES[selector % FILES.length];
        String content = new String(bytes, StandardCharsets.UTF_8);
        ParseRequest request = new ParseRequest(file, Path.of(file), content,
                sha256(bytes), languageFor(file));

        Optional<RepositoryParser> parser = PARSERS.parserFor(request);
        assertTrue(parser.isPresent(), "test input must select a registered parser for " + file);
        assertDoesNotThrow(() -> parser.orElseThrow().parse(request),
                () -> parser.orElseThrow().displayName() + " rejected malformed input with an exception");
    }

    static Stream<byte[]> seeds() {
        return Stream.of(
                new byte[0],
                "class Broken {".getBytes(StandardCharsets.UTF_8),
                "package body Broken is".getBytes(StandardCharsets.UTF_8),
                "SELECT * FROM".getBytes(StandardCharsets.UTF_8),
                "<project><broken>".getBytes(StandardCharsets.UTF_8),
                new byte[] {0, (byte) 0xff, 0, 1, 2, 3});
    }

    private static String languageFor(String file) {
        if (file.endsWith(".java")) {
            return "java";
        }
        if (file.endsWith(".adb") || file.endsWith(".ads")) {
            return "ada";
        }
        if (file.endsWith(".sql")) {
            return "sql";
        }
        return "config";
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
