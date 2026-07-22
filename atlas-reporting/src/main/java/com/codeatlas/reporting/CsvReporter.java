package com.codeatlas.reporting;

/**
 * Emits the two most spreadsheet-friendly findings &mdash; dead code and complexity
 * &mdash; as CSV, for triage in Excel or import into other tools.
 */
public final class CsvReporter {

    public String renderDeadCode(ReportData data) {
        StringBuilder sb = new StringBuilder("stableId,qualifiedName,kind,confidence,location,recommendation\n");
        data.analysis().deadCode().forEach(c -> sb
                .append(csv(c.stableId())).append(',')
                .append(csv(c.qualifiedName())).append(',')
                .append(csv(c.kind().name())).append(',')
                .append(c.confidence()).append(',')
                .append(csv(c.location().toString())).append(',')
                .append(csv(c.recommendation())).append('\n'));
        return sb.toString();
    }

    public String renderComplexity(ReportData data) {
        StringBuilder sb = new StringBuilder("stableId,qualifiedName,complexity,risk,location\n");
        data.analysis().complexityHotspots().forEach(h -> sb
                .append(csv(h.stableId())).append(',')
                .append(csv(h.qualifiedName())).append(',')
                .append(h.complexity()).append(',')
                .append(csv(h.risk().name())).append(',')
                .append(csv(h.location().toString())).append('\n'));
        return sb.toString();
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        // Spreadsheet readers can evaluate formula prefixes even in quoted cells.
        if (!value.isEmpty() && isFormulaPrefix(value.charAt(0))) {
            value = "'" + value;
        }
        if (value.contains(",") || value.contains("\"")
                || value.contains("\r") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean isFormulaPrefix(char first) {
        return first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r' || first == '\n';
    }
}
