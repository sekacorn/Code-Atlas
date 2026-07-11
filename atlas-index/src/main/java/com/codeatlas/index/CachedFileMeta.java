package com.codeatlas.index;

/**
 * Cache header for one analyzed file: which parser (and version) produced its
 * facts, for which exact content, with what outcome and line statistics.
 *
 * <p>A file's cached facts may be reused on a later scan only when the content
 * hash, parser id and parser version all still match — the conservative
 * definition of "nothing that could change the parse has changed".
 *
 * @param path          repository-relative path
 * @param contentHash   SHA-256 of the file content the facts were produced from
 * @param parserId      parser that produced the facts, or {@code null} if no
 *                      parser claimed the file
 * @param parserVersion version of that parser, or {@code null}
 * @param parseStatus   {@link #ANALYZED}, {@link #FAILED}, {@link #SKIPPED} or
 *                      {@link #UNREADABLE}
 * @param totalLines    total lines counted at parse time
 * @param commentLines  comment lines counted at parse time
 * @param blankLines    blank lines counted at parse time
 */
public record CachedFileMeta(String path,
                             String contentHash,
                             String parserId,
                             String parserVersion,
                             String parseStatus,
                             int totalLines,
                             int commentLines,
                             int blankLines) {

    public static final String ANALYZED = "ANALYZED";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";
    public static final String UNREADABLE = "UNREADABLE";
}
