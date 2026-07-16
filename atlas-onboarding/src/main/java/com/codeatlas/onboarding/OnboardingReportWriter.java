package com.codeatlas.onboarding;

import com.codeatlas.onboarding.model.OnboardingResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the onboarding report to disk, always <em>outside</em> the analyzed
 * repository. Produces {@code onboarding-report.json} (deterministic) and
 * {@code onboarding-report.html} (self-contained); {@code onboarding-report.txt}
 * is written too. Never writes into the analyzed repository - callers pass an
 * output directory that must not be inside it.
 */
public final class OnboardingReportWriter {

    /** True when {@code outputDir} is the analyzed repository or lies inside it. */
    public static boolean isInsideRepository(Path outputDir, Path repository) {
        Path out = outputDir.toAbsolutePath().normalize();
        Path repo = repository.toAbsolutePath().normalize();
        return out.equals(repo) || out.startsWith(repo);
    }

    /** Guards against writing report files into the analyzed repository. */
    public static void ensureOutsideRepository(Path outputDir, Path repository) {
        if (isInsideRepository(outputDir, repository)) {
            throw new IllegalArgumentException("Refusing to write onboarding reports inside the analyzed "
                    + "repository (" + repository.toAbsolutePath().normalize()
                    + "); choose an --output directory outside it.");
        }
    }

    public List<Path> writeAll(OnboardingResult result, Path outputDir, String generatedAtLabel) {
        try {
            Files.createDirectories(outputDir);
            List<Path> written = new ArrayList<>();
            written.add(write(outputDir.resolve("onboarding-report.json"), OnboardingReport.toJson(result)));
            written.add(write(outputDir.resolve("onboarding-report.html"),
                    OnboardingReport.toHtml(result, generatedAtLabel)));
            written.add(write(outputDir.resolve("onboarding-report.txt"), OnboardingReport.toText(result)));
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write onboarding reports to " + outputDir, e);
        }
    }

    private static Path write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
