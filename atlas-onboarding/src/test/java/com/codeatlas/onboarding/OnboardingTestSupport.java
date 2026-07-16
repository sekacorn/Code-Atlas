package com.codeatlas.onboarding;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.core.CodeAtlasPipeline;
import com.codeatlas.core.PipelineConfig;
import com.codeatlas.core.PipelineResult;
import com.codeatlas.tools.AtlasToolApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Shared helpers for the onboarding tests: scan a repository into a temporary
 * file-backed index and open it read-only (exactly as {@code atlas onboard} does),
 * and hash a directory tree to prove analysis never modified it.
 */
final class OnboardingTestSupport {

    private OnboardingTestSupport() {
    }

    record Scanned(AtlasToolApi api, AnalysisCoverage coverage, String scanId) {
    }

    static Scanned scan(Path repo, Path indexBase) throws IOException {
        Files.createDirectories(indexBase.getParent());
        PipelineResult pr = CodeAtlasPipeline.withDiscoveredParsers()
                .run(repo, PipelineConfig.builder().indexPath(indexBase).build());
        return new Scanned(AtlasToolApi.open(indexBase), pr.coverage(), pr.scanId());
    }

    /** A content hash of every file under {@code root} (path + bytes), order-independent. */
    static String hashTree(Path root) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
                for (Path p : sorted) {
                    md.update(root.relativize(p).toString().replace('\\', '/').getBytes());
                    md.update(Files.readAllBytes(p));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static long countFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
