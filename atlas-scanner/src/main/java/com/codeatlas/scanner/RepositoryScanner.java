package com.codeatlas.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.file.FileVisitOption.FOLLOW_LINKS;

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
        long deadlineNanos = deadlineFrom(options.maxDurationMillis());

        List<Candidate> candidates = walk(root, options, deadlineNanos);
        log.info("Discovered {} candidate files under {}", candidates.size(), root);

        List<ScannedFile> files = hashInParallel(candidates, options, deadlineNanos);

        long totalSize = files.stream().mapToLong(ScannedFile::sizeBytes).sum();
        long duration = System.currentTimeMillis() - start;
        log.info("Scan complete: {} files, {} bytes, {} ms", files.size(), totalSize, duration);
        return new ScanResult(root, List.copyOf(files), totalSize, duration);
    }

    // A file found during the walk, before its (expensive) content hash is computed.
    private record Candidate(Path absolute, String relative, long size,
                             LanguageDetector.Detection detection) {
    }

    private List<Candidate> walk(Path root, ScanOptions options, long deadlineNanos) {
        List<Candidate> candidates = new ArrayList<>();
        long[] acceptedBytes = {0L};
        try {
            EnumSet<FileVisitOption> visitOptions = options.followSymlinks()
                    ? EnumSet.of(FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class);
            Files.walkFileTree(root, visitOptions, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkDeadline(deadlineNanos, "walking the repository");
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path fileName = dir.getFileName();
                    if (fileName == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = fileName.toString();
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
                    checkDeadline(deadlineNanos, "walking the repository");
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > options.maxFileSizeBytes()) {
                        log.debug("Skipping oversized file ({} bytes): {}", attrs.size(), file);
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    Path fileName = file.getFileName();
                    if (fileName == null) {
                        log.warn("Skipping file with no file name: {}", file);
                        return FileVisitResult.CONTINUE;
                    }
                    LanguageDetector.Detection detection = detector.detect(fileName.toString());
                    if (candidates.size() >= options.maxFiles()) {
                        throw new ScanException("Scan file limit exceeded (maximum "
                                + options.maxFiles() + ") at " + relative);
                    }
                    if (attrs.size() > options.maxTotalBytes() - acceptedBytes[0]) {
                        throw new ScanException("Scan byte limit exceeded (maximum "
                                + options.maxTotalBytes() + " bytes) at " + relative);
                    }
                    candidates.add(new Candidate(file, relative, attrs.size(), detection));
                    acceptedBytes[0] += attrs.size();
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

    private List<ScannedFile> hashInParallel(List<Candidate> candidates, ScanOptions options,
                                             long deadlineNanos) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(options.threads(), candidates.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ScannedFile>> futures = new ArrayList<>(candidates.size());
            for (Candidate c : candidates) {
                futures.add(pool.submit(() -> toScannedFile(c, deadlineNanos)));
            }
            List<ScannedFile> results = new ArrayList<>(candidates.size());
            // Preserve walk order; task completion timing must not change scan output.
            for (Future<ScannedFile> f : futures) {
                try {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new TimeoutException("scan deadline reached");
                    }
                    ScannedFile sf = f.get(remaining, TimeUnit.NANOSECONDS);
                    if (sf != null) {
                        results.add(sf);
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new ScanException("Interrupted while hashing repository files", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof ScanException scanException) {
                        pool.shutdownNow();
                        throw scanException;
                    }
                    log.warn("Hashing failed: {}", cause.getMessage());
                } catch (TimeoutException e) {
                    pool.shutdownNow();
                    throw new ScanException("Scan duration limit exceeded while hashing repository files", e);
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private ScannedFile toScannedFile(Candidate c, long deadlineNanos) {
        try {
            String hash = sha256(c.absolute(), c.size(), deadlineNanos);
            return new ScannedFile(c.relative(), c.absolute(),
                    c.detection().languageId(), c.detection().category(),
                    c.size(), hash);
        } catch (IOException e) {
            log.warn("Cannot hash {}: {}", c.absolute(), e.getMessage());
            return null;
        }
    }

    static String sha256(Path file, long expectedSize, long deadlineNanos) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkDeadline(deadlineNanos, "hashing repository files");
                if (read > expectedSize - total) {
                    throw new ScanException("File changed while it was being scanned: " + file);
                }
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        if (total != expectedSize) {
            throw new ScanException("File changed while it was being scanned: " + file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long deadlineFrom(long durationMillis) {
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
        long now = System.nanoTime();
        return durationNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
    }

    private static void checkDeadline(long deadlineNanos, String operation) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new ScanException("Scan duration limit exceeded while " + operation);
        }
    }
}
