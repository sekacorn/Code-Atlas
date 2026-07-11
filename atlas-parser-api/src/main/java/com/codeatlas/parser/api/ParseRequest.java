package com.codeatlas.parser.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single file handed to a parser, together with everything the parser needs so
 * it never has to touch the filesystem itself.
 *
 * <p>The scanner has already read and hashed the file, so the content is provided
 * in-memory. This keeps parsers pure (easy to unit-test with a string of source)
 * and avoids double I/O over large repositories.
 *
 * @param relativePath repository-relative path with forward slashes (used in the model)
 * @param absolutePath absolute path on disk (for parsers that need a real file handle)
 * @param content      full decoded text content of the file
 * @param contentHash  stable content hash from the scanner (for incremental caching)
 * @param languageId   language id the scanner detected, or {@code "unknown"}
 */
public record ParseRequest(String relativePath,
                           Path absolutePath,
                           String content,
                           String contentHash,
                           String languageId) {

    public ParseRequest {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
    }

    /** File extension in lower case without the dot, or empty string when none. */
    public String extension() {
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        String name = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
