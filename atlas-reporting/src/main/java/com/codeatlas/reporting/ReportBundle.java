package com.codeatlas.reporting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the full report set (HTML, JSON, CSV) into an output directory.
 */
public final class ReportBundle {

    private final HtmlReporter html = new HtmlReporter();
    private final JsonReporter json = new JsonReporter();
    private final CsvReporter csv = new CsvReporter();

    /** @return the files written, HTML first. */
    public List<Path> writeAll(ReportData data, Path outputDir) {
        try {
            Files.createDirectories(outputDir);
            List<Path> written = new ArrayList<>();
            written.add(write(outputDir.resolve("report.html"), html.render(data)));
            written.add(write(outputDir.resolve("report.json"), json.render(data)));
            written.add(write(outputDir.resolve("dead-code.csv"), csv.renderDeadCode(data)));
            written.add(write(outputDir.resolve("complexity.csv"), csv.renderComplexity(data)));
            return written;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write reports to " + outputDir, e);
        }
    }

    private Path write(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
