package com.codeatlas.core;

import com.codeatlas.analysis.AnalysisCoverage;
import com.codeatlas.analysis.AnalysisEngine;
import com.codeatlas.analysis.AnalysisResult;
import com.codeatlas.index.AtlasStore;
import com.codeatlas.index.ChangeSet;
import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.Relationship;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The end-to-end pipeline that answers the platform's core questions:
 * <em>what exists, how it connects, what depends on what, what is unused</em>.
 *
 * <pre>
 *   scan → read+parse (parallel) → merge into unified model → link cross-refs
 *        → persist to local index → analyze → assemble report data
 * </pre>
 *
 * Every stage is deterministic and offline. The optional AI layer, if ever
 * enabled, consumes only the {@link AnalysisResult} produced here &mdash; it never
 * scans source directly.
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
        ScanResult scan = scanner.scan(repositoryRoot, config.scanOptions());

        // Incremental view: compare against the stored index (if any) before we rebuild.
        Map<String, String> pathToHash = new HashMap<>();
        for (ScannedFile f : scan.files()) {
            pathToHash.put(f.relativePath(), f.contentHash());
        }

        SoftwareModel model = new SoftwareModel();
        // Parse every file in parallel; each task both parses (if supported) and
        // enriches the FILE entity with line statistics. Per-file outcomes are
        // accumulated for honest coverage reporting.
        CoverageAccumulator cov = new CoverageAccumulator();
        scan.files().parallelStream().forEach(f -> processFile(f, model, cov));

        // PROJECT root + physical containment of every file. Its identity is the
        // repository name (never the absolute path, which is machine-specific).
        String projectName = repositoryRoot.getFileName() != null
                ? repositoryRoot.getFileName().toString() : repositoryRoot.toString();
        Entity project = Entity.builder(EntityKind.PROJECT, projectName)
                .qualifiedName(projectName)
                .language("n/a")
                .build();
        model.addEntity(project);
        for (Entity file : model.entitiesOfKind(EntityKind.FILE)) {
            model.addRelationship(Relationship.builder(RelationshipKind.CONTAINS, project.id(), file.id()).build());
        }

        LinkStats linkStats = linker.link(model);

        ChangeSet changes = persist(model, pathToHash, config);

        AnalysisResult analysis = new AnalysisEngine(
                config.complexityThreshold(), config.deadCodeMinConfidence()).analyze(model);

        AnalysisCoverage coverage = new AnalysisCoverage(
                scan.fileCount(), cov.analyzed.get(), cov.skipped.get(), cov.failed.get(),
                cov.unreadable.get(), cov.unsupportedExtensions.size(),
                linkStats.resolved(), linkStats.unresolved(), linkStats.ambiguous());

        String repoName = project.name();
        ReportData reportData = new ReportData(repoName, Instant.now(),
                scan.durationMillis(), model, analysis, coverage);

        log.info("Pipeline finished in {} ms: {} entities, {} relationships (coverage partial={})",
                System.currentTimeMillis() - start, model.entityCount(), model.relationshipCount(),
                coverage.isPartial());
        return new PipelineResult(model, analysis, coverage, scan, changes, reportData);
    }

    /** Thread-safe tallies of per-file parse outcomes gathered during the parallel pass. */
    private static final class CoverageAccumulator {
        final AtomicInteger analyzed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger unreadable = new AtomicInteger();
        final Set<String> unsupportedExtensions = ConcurrentHashMap.newKeySet();
    }

    private void processFile(ScannedFile f, SoftwareModel model, CoverageAccumulator cov) {
        String content = readContent(f);
        if (content == null) {
            // Unreadable/binary: still record the file so counts are honest.
            model.addEntity(fileEntity(f, LineStats.count("", f.languageId())));
            cov.unreadable.incrementAndGet();
            return;
        }
        LineStats stats = LineStats.count(content, f.languageId());

        ParseRequest request = new ParseRequest(f.relativePath(), f.absolutePath(),
                content, f.contentHash(), f.languageId());
        Optional<RepositoryParser> parser = parsers.parserFor(request);
        if (parser.isPresent()) {
            ParseResult result = parser.get().parse(request);
            model.addEntities(result.entities());
            model.addRelationships(result.relationships());
            if (result.hasErrors()) {
                cov.failed.incrementAndGet();
            } else {
                cov.analyzed.incrementAndGet();
            }
        } else {
            cov.skipped.incrementAndGet();
            cov.unsupportedExtensions.add(request.extension());
        }
        // Enrich (or create) the FILE entity; added last so it wins over any the parser emitted.
        model.addEntity(fileEntity(f, stats));
    }

    private Entity fileEntity(ScannedFile f, LineStats stats) {
        return Entity.builder(EntityKind.FILE, fileName(f.relativePath()))
                .qualifiedName(f.relativePath())
                .language(f.languageId())
                .location(SourceLocation.ofFile(f.relativePath()))
                .attribute(Entity.Attributes.FILE_HASH, f.contentHash())
                .attribute(Entity.Attributes.LINES_OF_CODE, stats.total())
                .attribute(Entity.Attributes.COMMENT_LINES, stats.comment())
                .attribute(Entity.Attributes.BLANK_LINES, stats.blank())
                .attribute("category", f.category().name())
                .build();
    }

    private ChangeSet persist(SoftwareModel model, Map<String, String> pathToHash, PipelineConfig config) {
        Path indexPath = config.indexPath().orElse(null);
        try (AtlasStore store = indexPath != null ? AtlasStore.atPath(indexPath) : AtlasStore.inMemory()) {
            ChangeSet changes = store.computeChanges(pathToHash);
            store.saveModel(model);
            store.saveHashes(pathToHash);
            if (indexPath != null && !changes.isFirstRun()) {
                log.info("Incremental view: {} added, {} changed, {} removed, {} unchanged",
                        changes.added().size(), changes.changed().size(),
                        changes.removed().size(), changes.unchanged().size());
            }
            return changes;
        }
    }

    private String readContent(ScannedFile f) {
        try {
            byte[] bytes = Files.readAllBytes(f.absolutePath());
            // Skip files that look binary (contain NUL in the first block).
            int scan = Math.min(bytes.length, 8000);
            for (int i = 0; i < scan; i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", f.relativePath(), e.getMessage());
            return null;
        }
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
