package com.codeatlas.scanner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The outcome of scanning a repository: the discovered files plus headline stats
 * that feed straight into the dashboard's "how large is the system" answer.
 */
public record ScanResult(Path root,
                         List<ScannedFile> files,
                         long totalSizeBytes,
                         long durationMillis) {

    /** File counts keyed by language id, sorted for stable reporting. */
    public Map<String, Integer> filesByLanguage() {
        Map<String, Integer> counts = new TreeMap<>();
        for (ScannedFile f : files) {
            counts.merge(f.languageId(), 1, Integer::sum);
        }
        return counts;
    }

    /** File counts keyed by category. */
    public Map<FileCategory, Integer> filesByCategory() {
        Map<FileCategory, Integer> counts = new TreeMap<>();
        for (ScannedFile f : files) {
            counts.merge(f.category(), 1, Integer::sum);
        }
        return counts;
    }

    public int fileCount() {
        return files.size();
    }

    public List<ScannedFile> sourceFiles() {
        return files.stream().filter(ScannedFile::isSource).toList();
    }
}
