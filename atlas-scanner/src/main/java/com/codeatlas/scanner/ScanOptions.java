package com.codeatlas.scanner;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tunables for a scan. Defaults match the platform spec: sensible directory
 * exclusions, a size cap to skip binary blobs, and parallelism across CPU cores.
 */
public final class ScanOptions {

    /** Directory names excluded by default (build output, VCS metadata, deps). */
    public static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "target", "build", "out", "dist",
            "node_modules", "bin", "obj",
            ".idea", ".vscode", ".gradle", ".mvn"
    );

    private final Set<String> excludedDirs;
    private final long maxFileSizeBytes;
    private final int threads;
    private final boolean followSymlinks;

    private ScanOptions(Builder b) {
        this.excludedDirs = Set.copyOf(b.excludedDirs);
        this.maxFileSizeBytes = b.maxFileSizeBytes;
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
        private long maxFileSizeBytes = 8L * 1024 * 1024; // 8 MiB
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
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
            this.maxFileSizeBytes = bytes;
            return this;
        }

        public Builder threads(int threads) {
            this.threads = Math.max(1, threads);
            return this;
        }

        public Builder followSymlinks(boolean followSymlinks) {
            this.followSymlinks = followSymlinks;
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(this);
        }
    }
}
