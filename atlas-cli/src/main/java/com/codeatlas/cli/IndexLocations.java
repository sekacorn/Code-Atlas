package com.codeatlas.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the default on-disk location of a repository's persistent index.
 *
 * <p>Indexes live under the user's home directory
 * ({@code ~/.code-atlas/index/<repo-name>-<hash8>/atlas}), never inside the
 * analyzed repository — analysis must not modify the analyzed tree. The hash
 * suffix (of the absolute repository path) keeps same-named repositories apart.
 * No administrator privileges are needed and nothing leaves the machine.
 */
final class IndexLocations {

    private IndexLocations() {
    }

    static Path defaultIndexFor(Path repositoryRoot) {
        Path abs = repositoryRoot.toAbsolutePath().normalize();
        String name = displayName(abs, "repo");
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("user.home"), ".code-atlas", "index",
                safe + "-" + hash8(abs.toString()), "atlas");
    }

    /**
     * Where onboarding reports go when {@code ./atlas-onboarding-report} would fall
     * inside the analyzed repository (e.g. {@code atlas onboard .} run from the
     * repository root). Reports must never be written into the analyzed tree.
     */
    static Path defaultOnboardingOutputFor(Path repositoryRoot) {
        return Path.of(System.getProperty("user.home"), ".code-atlas", "onboarding",
                repositoryKey(repositoryRoot));
    }

    /**
     * A deterministic, location-derived key that distinguishes same-named
     * repositories without exposing the full path (name + 8-hex path hash).
     */
    static String repositoryKey(Path repositoryRoot) {
        Path abs = repositoryRoot.toAbsolutePath().normalize();
        String name = displayName(abs, "repo");
        return name.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + hash8(abs.toString());
    }

    static String repositoryDisplayName(Path repositoryRoot) {
        return displayName(repositoryRoot.toAbsolutePath().normalize(), "repository");
    }

    static boolean indexDirectoryExists(Path index) {
        return Files.exists(indexDirectory(index));
    }

    static Path indexDirectory(Path index) {
        Path absolute = index.toAbsolutePath();
        Path parent = absolute.getParent();
        return parent != null ? parent : absolute;
    }

    private static String displayName(Path path, String fallback) {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : fallback;
    }

    private static String hash8(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
