package com.codeatlas.index;

/**
 * One scan run recorded in the index. Rows are kept for every run (successful or
 * not), so scan history stays distinguishable even though only the latest
 * completed scan's model snapshot is retained.
 *
 * @param id        internal sequential row id
 * @param scanKey   deterministic content-derived scan identifier ("scan-…"),
 *                  identical for identical repository content
 * @param status    {@link #IN_PROGRESS}, {@link #COMPLETED} or {@link #FAILED}
 * @param fileCount number of files in the scan
 */
public record ScanRecord(long id, String scanKey, String status, int fileCount) {

    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
}
