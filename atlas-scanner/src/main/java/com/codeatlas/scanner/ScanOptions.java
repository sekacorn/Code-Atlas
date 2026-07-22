package com.codeatlas.scanner;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tunables for a scan. Defaults match the platform spec: sensible directory
 * exclusions, a size cap to skip binary blobs, and parallelism across CPU cores.
 */
public final class ScanOptions {

    private static final int MAX_DEFAULT_THREADS = 32;

    public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 8L * 1024 * 1024;
    public static final int DEFAULT_MAX_FILES = 250_000;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024;
    public static final long DEFAULT_MAX_DURATION_MILLIS = 30L * 60 * 1000;

    public static final long HARDENED_MAX_FILE_SIZE_BYTES = 4L * 1024 * 1024;
    public static final int HARDENED_MAX_FILES = 100_000;
    public static final long HARDENED_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long HARDENED_MAX_DURATION_MILLIS = 15L * 60 * 1000;
    public static final int HARDENED_MAX_THREADS = 8;

    /** Directory names excluded by default (build output, VCS metadata, deps). */
    public static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "target", "build", "out", "dist",
            "node_modules", "bin", "obj",
            ".idea", ".vscode", ".gradle", ".mvn"
    );

    private final Set<String> excludedDirs;
    private final long maxFileSizeBytes;
    private final int maxFiles;
    private final long maxTotalBytes;
    private final long maxDurationMillis;
    private final int threads;
    private final boolean followSymlinks;

    private ScanOptions(Builder b) {
        this.excludedDirs = Set.copyOf(b.excludedDirs);
        this.maxFileSizeBytes = b.maxFileSizeBytes;
        this.maxFiles = b.maxFiles;
        this.maxTotalBytes = b.maxTotalBytes;
        this.maxDurationMillis = b.maxDurationMillis;
        this.threads = b.threads;
        this.followSymlinks = b.followSymlinks;
    }

    public static ScanOptions defaults() {
        return builder().build();
    }

    public Set<String> excludedDirs() {
        return excludedDirs;
    }

    public long maxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public int maxFiles() {
        return maxFiles;
    }

    public long maxTotalBytes() {
        return maxTotalBytes;
    }

    public long maxDurationMillis() {
        return maxDurationMillis;
    }

    public int threads() {
        return threads;
    }

    public boolean followSymlinks() {
        return followSymlinks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Set<String> excludedDirs = new LinkedHashSet<>(DEFAULT_EXCLUDED_DIRS);
        private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;
        private int maxFiles = DEFAULT_MAX_FILES;
        private long maxTotalBytes = DEFAULT_MAX_TOTAL_BYTES;
        private long maxDurationMillis = DEFAULT_MAX_DURATION_MILLIS;
        private int threads = Math.max(1,
                Math.min(MAX_DEFAULT_THREADS, Runtime.getRuntime().availableProcessors()));
        private boolean followSymlinks = false;

        public Builder excludeDir(String name) {
            excludedDirs.add(name);
            return this;
        }

        public Builder clearExclusions() {
            excludedDirs.clear();
            return this;
        }

        public Builder maxFileSizeBytes(long bytes) {
            requirePositive(bytes, "maxFileSizeBytes");
            this.maxFileSizeBytes = bytes;
            return this;
        }

        public Builder maxFiles(int maxFiles) {
            requirePositive(maxFiles, "maxFiles");
            this.maxFiles = maxFiles;
            return this;
        }

        public Builder maxTotalBytes(long bytes) {
            requirePositive(bytes, "maxTotalBytes");
            this.maxTotalBytes = bytes;
            return this;
        }

        public Builder maxDurationMillis(long millis) {
            requirePositive(millis, "maxDurationMillis");
            this.maxDurationMillis = millis;
            return this;
        }

        public Builder threads(int threads) {
            requirePositive(threads, "threads");
            this.threads = threads;
            return this;
        }

        public Builder followSymlinks(boolean followSymlinks) {
            this.followSymlinks = followSymlinks;
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(this);
        }

        private static void requirePositive(long value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be greater than zero");
            }
        }
    }
}
