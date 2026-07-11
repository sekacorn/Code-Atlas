package com.codeatlas.scanner;

import java.nio.file.Path;

/**
 * One file discovered by the scanner, with everything needed to decide whether to
 * (re)parse it and how to classify it &mdash; but without its content, so a scan of
 * a huge repository stays cheap in memory. Content is read on demand at parse time.
 *
 * @param relativePath repository-relative path, forward slashes
 * @param absolutePath absolute path on disk
 * @param languageId   detected language id, or {@code "unknown"}
 * @param category     coarse file category
 * @param sizeBytes    file size in bytes
 * @param contentHash  SHA-256 hex of the file content (basis for incremental scans)
 */
public record ScannedFile(String relativePath,
                          Path absolutePath,
                          String languageId,
                          FileCategory category,
                          long sizeBytes,
                          String contentHash) {

    public boolean isSource() {
        return category == FileCategory.SOURCE;
    }
}
