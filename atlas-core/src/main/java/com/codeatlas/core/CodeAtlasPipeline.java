package com.codeatlas.core;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.index.CacheEntry;
import com.codeatlas.index.CachedFileMeta;
import com.codeatlas.index.ChangeSet;
import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.SoftwareModel;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;
import com.codeatlas.reporting.ReportData;
import com.codeatlas.scanner.RepositoryScanner;
import com.codeatlas.scanner.ScanResult;
import com.codeatlas.scanner.ScannedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Set;

/**
 * The end-to-end pipeline that answers the platform's core questions:
 * <em>what exists, how it connects, what depends on what, what is unused</em>.
 *
 * <pre>
 *   scan → change detection → parse changed files (parallel) + reuse cached facts
 *        → merge into unified model → link cross-refs
 *        → persist scan atomically → analyze → assemble report data
 * </pre>
 *
 * Every stage is deterministic and offline. Reuse is conservative: a file's cached
 * facts are used only when its content hash, parser id and parser version all
 * match, and reusing them is byte-for-byte equivalent to re-parsing. A scan that
 * fails never replaces the previous completed scan in the index.
 */
public final class CodeAtlasPipeline {

    private static final Logger log = LoggerFactory.getLogger(CodeAtlasPipeline.class);

    private final ParserRegistry parsers;
    private final RepositoryScanner scanner = new RepositoryScanner();
    private final Linker linker = new Linker();

    public CodeAtlasPipeline(ParserRegistry parsers) {
        this.parsers = parsers;
    }

    /** Convenience constructor that auto-discovers parsers from the classpath. */
    public static CodeAtlasPipeline withDiscoveredParsers() {
        return new CodeAtlasPipeline(ParserRegistry.discover());
    }

    public PipelineResult run(Path repositoryRoot, PipelineConfig config) {
        long start = System.currentTimeMillis();
        long deadlineNanos = deadlineFrom(config.scanOptions().maxDurationMillis());
        ScanResult scan = scanner.scan(repositoryRoot, config.scanOptions());
        checkDeadline(deadlineNanos, "starting model construction");

        Map<String, String> pathToHash = new HashMap<>();
        for (ScannedFile f : scan.files()) {
            pathToHash.put(f.relativePath(), f.contentHash());
        }
        String scanId = scanIdFor(pathToHash);

        Path indexPath = config.indexPath().orElse(null);
        try (AtlasStore store = indexPath != null ? AtlasStore.atPath(indexPath) : AtlasStore.inMemory()) {
            ChangeSet changes = store.computeChanges(pathToHash);
            long scanRowId = store.beginScan(scanId, repositoryRoot.toString(), scan.fileCount());
            try {
                PipelineResult result = buildAndPersist(repositoryRoot, config, scan, pathToHash,
                        scanId, scanRowId, store, changes, start, deadlineNanos);
                return result;
            } catch (RuntimeException e) {
                store.markScanFailed(scanRowId);
                throw e;
            }
        }
    }

    private PipelineResult buildAndPersist(Path repositoryRoot, PipelineConfig config, ScanResult scan,
                                           Map<String, String> pathToHash, String scanId, long scanRowId,
                                           AtlasStore store, ChangeSet changes, long start,
                                           long deadlineNanos) {
        SoftwareModel model = new SoftwareModel();
        Coverage cov = new Coverage();

        // Partition files: reuse cached facts where content, parser id and parser
        // version are unchanged; parse the rest.
        Map<String, CachedFileMeta> cacheIndex = store.cacheIndex();
        List<ScannedFile> toParse = new ArrayList<>();
        List<CachedFileMeta> toReuse = new ArrayList<>();
        for (ScannedFile f : scan.files()) {
            CachedFileMeta cached = cacheIndex.get(f.relativePath());
            if (cached != null && isReusable(cached, f)) {
                toReuse.add(cached);
            } else {
                toParse.add(f);
            }
        }

        // Parse changed/new files in parallel (pure CPU + file reads, no store access).
        Map<String, ParsedFile> parsed = parseFiles(toParse, config.scanOptions().threads(), deadlineNanos);
        checkDeadline(deadlineNanos, "merging parser results");

        // Merge cached facts (single-threaded store reads), then fresh results.
        List<CacheEntry> newCacheEntries = new ArrayList<>();
        for (CachedFileMeta meta : toReuse) {
            CacheEntry entry = store.loadCachedFacts(meta);
            model.addEntities(entry.entities());
            model.addRelationships(entry.relationships());
            model.addEntity(fileEntityFrom(meta, findFile(scan, meta.path())));
            cov.reused++;
            cov.countStatus(meta.parseStatus(), meta.path());
        }
        // Deterministic merge order for fresh results.
        for (var e : new TreeMap<>(parsed).entrySet()) {
            ParsedFile pf = e.getValue();
            if (pf.result != null) {
                model.addEntities(pf.result.entities());
                model.addRelationships(pf.result.relationships());
            }
            model.addEntity(fileEntityFrom(pf.meta, pf.file));
            cov.countStatus(pf.meta.parseStatus(), pf.meta.path());
            newCacheEntries.add(new CacheEntry(pf.meta,
                    pf.result != null ? pf.result.entities() : List.of(),
                    pf.result != null ? pf.result.relationships() : List.of()));
        }

        // PROJECT root + physical containment of every file. Its identity is the
        // repository name (never the absolute path, which is machine-specific).
        Path projectFileName = repositoryRoot.getFileName();
        String projectName = projectFileName != null ? projectFileName.toString() : repositoryRoot.toString();
        Entity project = Entity.builder(EntityKind.PROJECT, projectName)
                .qualifiedName(projectName)
                .language("n/a")
                .build();
        model.addEntity(project);
        for (Entity file : model.entitiesOfKind(EntityKind.FILE)) {
            model.addRelationship(Relationship.builder(RelationshipKind.CONTAINS, project.id(), file.id()).build());
        }

        LinkStats linkStats = linker.link(model);
        checkDeadline(deadlineNanos, "linking references");

        // Build membership is a cross-file fact: assign each file to the deepest
        // build module whose directory contains it. Runs after linking so module
        // entities exist and their declared dependencies are already resolved.
        new BuildMembershipLinker().apply(model);
        checkDeadline(deadlineNanos, "assigning build membership");

        // Lineage rules run after linking (they need resolved type/impl edges) and
        // before persistence (lineage facts belong to the scan snapshot).
        new com.codeatlas.analysis.lineage.LineageAnalyzer().apply(model);
        new com.codeatlas.analysis.lineage.AdaLineageAnalyzer().apply(model);
        checkDeadline(deadlineNanos, "analyzing data lineage");

        checkDeadline(deadlineNanos, "starting completed-scan persistence");
        store.persistCompletedScan(scanRowId, model, pathToHash, newCacheEntries, pathToHash.keySet());

        AnalysisResult analysis = new AnalysisEngine(
                config.complexityThreshold(), config.deadCodeMinConfidence()).analyze(model);

        AnalysisCoverage coverage = new AnalysisCoverage(
                scan.fileCount(), cov.analyzed, cov.skipped, cov.failed, cov.unreadable, cov.reused,
                cov.unsupportedExtensions.size(),
                linkStats.resolved(), linkStats.unresolved(), linkStats.ambiguous());

        ReportData reportData = new ReportData(project.name(), Instant.now(),
                scan.durationMillis(), scanId, model, analysis, coverage);

        log.info("Pipeline finished in {} ms: {} entities, {} relationships ({} parsed, {} reused, partial={})",
                System.currentTimeMillis() - start, model.entityCount(), model.relationshipCount(),
                parsed.size(), cov.reused, coverage.isPartial());
        return new PipelineResult(model, analysis, coverage, scan, changes, scanId, reportData);
    }

    /** Per-scan coverage tallies (single-threaded accumulation during merge). */
    private static final class Coverage {
        int analyzed;
        int skipped;
        int failed;
        int unreadable;
        int reused;
        final Set<String> unsupportedExtensions = new java.util.HashSet<>();

        void countStatus(String parseStatus, String path) {
            switch (parseStatus) {
                case CachedFileMeta.ANALYZED -> analyzed++;
                case CachedFileMeta.FAILED -> failed++;
                case CachedFileMeta.UNREADABLE -> unreadable++;
                default -> {
                    skipped++;
                    unsupportedExtensions.add(extensionOf(path));
                }
            }
        }
    }

    /** One freshly parsed file: its scan info, outcome header and raw parser facts. */
    private record ParsedFile(ScannedFile file, CachedFileMeta meta, ParseResult result) {
    }

    private Map<String, ParsedFile> parseFiles(List<ScannedFile> files, int configuredThreads,
                                               long deadlineNanos) {
        if (files.isEmpty()) {
            return Map.of();
        }
        int workers = Math.min(Math.max(1, configuredThreads), files.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<ParsedFile>> futures = new ArrayList<>(files.size());
            for (ScannedFile file : files) {
                futures.add(pool.submit(() -> parseFile(file)));
            }
            Map<String, ParsedFile> parsed = new HashMap<>();
            // Join in submission order so a failure always names the same file.
            for (int i = 0; i < futures.size(); i++) {
                try {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) {
                        throw new TimeoutException("scan deadline reached");
                    }
                    ParsedFile result = futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                    parsed.put(result.file().relativePath(), result);
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while parsing repository files", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    throw new IllegalStateException("Failed to parse " + files.get(i).relativePath(), cause);
                } catch (TimeoutException e) {
                    pool.shutdownNow();
                    throw new IllegalStateException("Scan duration limit exceeded while parsing "
                            + files.get(i).relativePath(), e);
                }
            }
            return parsed;
        } finally {
            pool.shutdownNow();
        }
    }

    private ParsedFile parseFile(ScannedFile f) {
        String content = readContent(f);
        if (content == null) {
            return new ParsedFile(f, new CachedFileMeta(f.relativePath(), f.contentHash(),
                    null, null, CachedFileMeta.UNREADABLE, 0, 0, 0), null);
        }
        LineStats stats = LineStats.count(content, f.languageId());
        ParseRequest request = new ParseRequest(f.relativePath(), f.absolutePath(),
                content, f.contentHash(), f.languageId());
        Optional<RepositoryParser> parser = parsers.parserFor(request);
        if (parser.isEmpty()) {
            return new ParsedFile(f, new CachedFileMeta(f.relativePath(), f.contentHash(),
                    null, null, CachedFileMeta.SKIPPED,
                    stats.total(), stats.comment(), stats.blank()), null);
        }
        ParseResult result = parser.get().parse(request);
        String status = result.hasErrors() ? CachedFileMeta.FAILED : CachedFileMeta.ANALYZED;
        return new ParsedFile(f, new CachedFileMeta(f.relativePath(), f.contentHash(),
                parser.get().languageId(), parser.get().parserVersion(), status,
                stats.total(), stats.comment(), stats.blank()), result);
    }

    /**
     * A cached file may be reused only when nothing that could change the parse
     * has changed: same content hash, same parser, same parser version. A file
     * whose current parser differs from the cached one (new parser installed,
     * parser upgraded) is re-parsed.
     */
    private boolean isReusable(CachedFileMeta cached, ScannedFile current) {
        if (!cached.contentHash().equals(current.contentHash())) {
            return false;
        }
        ParseRequest probe = new ParseRequest(current.relativePath(), current.absolutePath(),
                "", current.contentHash(), current.languageId());
        Optional<RepositoryParser> parser = parsers.parserFor(probe);
        String currentId = parser.map(RepositoryParser::languageId).orElse(null);
        String currentVersion = parser.map(RepositoryParser::parserVersion).orElse(null);
        return java.util.Objects.equals(cached.parserId(), currentId)
                && java.util.Objects.equals(cached.parserVersion(), currentVersion);
    }

    private static ScannedFile findFile(ScanResult scan, String path) {
        for (ScannedFile f : scan.files()) {
            if (f.relativePath().equals(path)) {
                return f;
            }
        }
        return null;
    }

    private Entity fileEntityFrom(CachedFileMeta meta, ScannedFile f) {
        Entity.Builder b = Entity.builder(EntityKind.FILE, fileName(meta.path()))
                .qualifiedName(meta.path())
                .language(f != null ? f.languageId() : "unknown")
                .location(SourceLocation.ofFile(meta.path()))
                .attribute(Entity.Attributes.FILE_HASH, meta.contentHash())
                .attribute(Entity.Attributes.LINES_OF_CODE, meta.totalLines())
                .attribute(Entity.Attributes.COMMENT_LINES, meta.commentLines())
                .attribute(Entity.Attributes.BLANK_LINES, meta.blankLines());
        if (f != null) {
            b.attribute("category", f.category().name());
        }
        return b.build();
    }

    /**
     * Deterministic, content-derived scan identifier: identical repository content
     * yields the identical scan id, so deterministic-output guarantees extend to
     * structures that embed it.
     */
    static String scanIdFor(Map<String, String> pathToHash) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        for (var e : new TreeMap<>(pathToHash).entrySet()) {
            digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return "scan-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

    String readContent(ScannedFile f) {
        if (f.sizeBytes() > Integer.MAX_VALUE - 8L) {
            throw new IllegalStateException("File is too large to parse safely: " + f.relativePath());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) f.sizeBytes());
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            try (InputStream input = Files.newInputStream(f.absolutePath())) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read > f.sizeBytes() - total) {
                        throw changedDuringScan(f);
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                }
            }
            if (total != f.sizeBytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(f.contentHash())) {
                throw changedDuringScan(f);
            }
            byte[] bytes = output.toByteArray();
            // Skip files that look binary (contain NUL in the first block).
            int scan = Math.min(bytes.length, 8000);
            for (int i = 0; i < scan; i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", f.relativePath(), e.getMessage());
            return null;
        }
    }

    private static IllegalStateException changedDuringScan(ScannedFile file) {
        return new IllegalStateException("File changed while it was being scanned: "
                + file.relativePath());
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String extensionOf(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static long deadlineFrom(long durationMillis) {
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMillis);
        long now = System.nanoTime();
        return durationNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationNanos;
    }

    private static void checkDeadline(long deadlineNanos, String operation) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException("Scan duration limit exceeded while " + operation);
        }
    }
}
