package com.codeatlas.model;

import java.util.Objects;

/**
 * A traceable pointer back into source: the evidence behind every finding.
 *
 * <p>The platform never makes a claim it cannot anchor to a file and, where
 * available, a line range. Columns are optional (0 means "unknown").
 *
 * @param filePath  repository-relative path, using forward slashes
 * @param startLine 1-based start line (0 if unknown)
 * @param endLine   1-based end line (0 if unknown)
 * @param startColumn 1-based start column (0 if unknown)
 * @param endColumn   1-based end column (0 if unknown)
 */
public record SourceLocation(String filePath, int startLine, int endLine, int startColumn, int endColumn) {

    public SourceLocation {
        Objects.requireNonNull(filePath, "filePath");
    }

    /** Convenience factory when only a file and line range are known. */
    public static SourceLocation of(String filePath, int startLine, int endLine) {
        return new SourceLocation(filePath, startLine, endLine, 0, 0);
    }

    /** Convenience factory for a whole-file location. */
    public static SourceLocation ofFile(String filePath) {
        return new SourceLocation(filePath, 0, 0, 0, 0);
    }

    /** Number of source lines spanned, or 0 when the range is unknown. */
    public int lineSpan() {
        if (startLine <= 0 || endLine <= 0) {
            return 0;
        }
        return Math.max(0, endLine - startLine + 1);
    }

    @Override
    public String toString() {
        if (startLine > 0) {
            return filePath + ":" + startLine;
        }
        return filePath;
    }
}
