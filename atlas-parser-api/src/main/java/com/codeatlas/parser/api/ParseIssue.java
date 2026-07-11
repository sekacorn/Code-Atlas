package com.codeatlas.parser.api;

/**
 * A non-fatal problem encountered while parsing (a syntax error the parser
 * recovered from, an unresolved symbol, an unsupported construct).
 *
 * <p>Issues are surfaced in reports so results stay auditable: a partially parsed
 * file is never silently presented as complete.
 */
public record ParseIssue(Severity severity, String message, int line) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public static ParseIssue warning(String message, int line) {
        return new ParseIssue(Severity.WARNING, message, line);
    }

    public static ParseIssue error(String message, int line) {
        return new ParseIssue(Severity.ERROR, message, line);
    }
}
