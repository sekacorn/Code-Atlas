package com.codeatlas.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Recursively scans a repository into a {@link ScanResult}.
 *
 * <p>Two phases keep it fast on large trees: a single-threaded directory walk
 * that applies exclusions and detects languages (cheap, I/O-light), followed by a
 * parallel hashing pass (the expensive part) across a fixed thread pool. Nothing
 * requires admin privileges and nothing leaves the machine.
 */
public final class RepositoryScanner {

    private static final Logger log = LoggerFactory.getLogger(RepositoryScanner.class);

    private final LanguageDetector detector = new LanguageDetector();

    public ScanResult scan(Path root, ScanOptions options) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        long start = System.currentTimeMillis();

        List<Candidate> candidates = walk(root, options);
        log.info("Discovered {} candidate files under {}", candidates.size(), root);

        List<ScannedFile> files = hashInParallel(candidates, options);

        long totalSize = files.stream().mapToLong(ScannedFile::sizeBytes).sum();
        long duration = System.currentTimeMillis() - start;
        log.info("Scan complete: {} files, {} bytes, {} ms", files.size(), totalSize, duration);
        return new ScanResult(root, List.copyOf(files), totalSize, duration);
    }

    // A file found during the walk, before its (expensive) content hash is computed.
    private record Candidate(Path absolute, String relative, long size,
                             LanguageDetector.Detection detection) {
    }

    private List<Candidate> walk(Path root, ScanOptions options) {
        List<Candidate> candidates = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (options.excludedDirs().contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!options.followSymlinks() && Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > options.maxFileSizeBytes()) {
                        log.debug("Skipping oversized file ({} bytes): {}", attrs.size(), file);
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    LanguageDetector.Detection detection = detector.detect(file.getFileName().toString());
                    candidates.add(new Candidate(file, relative, attrs.size(), detection));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Cannot access {}: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ScanException("Failed to walk repository " + root, e);
        }
        return candidates;
    }

    private List<ScannedFile> hashInParallel(List<Candidate> candidates, ScanOptions options) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(options.threads(), candidates.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ScannedFile>> futures = new ArrayList<>(candidates.size());
            for (Candidate c : candidates) {
                futures.add(pool.submit(() -> toScannedFile(c)));
            }
            List<ScannedFile> results = new ArrayList<>(candidates.size());
            for (Future<ScannedFile> f : futures) {
                try {
                    ScannedFile sf = f.get();
                    if (sf != null) {
                        results.add(sf);
                    }
                } catch (Exception e) {
                    log.warn("Hashing failed: {}", e.getMessage());
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private ScannedFile toScannedFile(Candidate c) {
        try {
            String hash = sha256(c.absolute());
            return new ScannedFile(c.relative(), c.absolute(),
                    c.detection().languageId(), c.detection().category(),
                    c.size(), hash);
        } catch (IOException e) {
            log.warn("Cannot hash {}: {}", c.absolute(), e.getMessage());
            return null;
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[16 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
