package com.codeatlas.cli;

import java.nio.charset.StandardCharsets;
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
        String name = abs.getFileName() != null ? abs.getFileName().toString() : "repo";
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("user.home"), ".code-atlas", "index",
                safe + "-" + hash8(abs.toString()), "atlas");
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
