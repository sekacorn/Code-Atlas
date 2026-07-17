package com.codeatlas.parser.java;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the tables a literal SQL string touches, and whether it reads or writes
 * them. This is how JDBC and {@code @Query} statements become data-lineage facts:
 * without it, a table is only reachable through a JPA mapping.
 *
 * <p>Only the <em>derived</em> facts (table name, read vs write) are kept — the SQL
 * text itself is never stored, matching the platform's rule that evidence is
 * locations and rules, never source text.
 *
 * <p>Limits (honest by design): this reads one literal statement at a time. SQL
 * assembled at runtime is only partly visible — the fragments that are literal are
 * still read, and the caller marks the result dynamic so confidence is lowered and
 * the gap is reported. Subqueries are covered only insofar as their {@code FROM}
 * clauses appear in the same literal; stored procedures and vendor extensions are
 * not modeled.
 */
final class SqlLiterals {

    private static final int CI = Pattern.CASE_INSENSITIVE;

    /** A string is treated as SQL only when it opens with a statement keyword. */
    private static final Pattern IS_SQL =
            Pattern.compile("^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH)\\b", CI);
    private static final Pattern FIRST_KEYWORD =
            Pattern.compile("^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH)\\b", CI);

    private static final Pattern FROM = Pattern.compile("\\bFROM\\s+([\\w.\"`]+)", CI);
    private static final Pattern JOIN = Pattern.compile("\\bJOIN\\s+([\\w.\"`]+)", CI);
    private static final Pattern INSERT_INTO = Pattern.compile("\\bINSERT\\s+INTO\\s+([\\w.\"`]+)", CI);
    private static final Pattern UPDATE_TARGET = Pattern.compile("\\bUPDATE\\s+([\\w.\"`]+)", CI);
    private static final Pattern DELETE_FROM = Pattern.compile("\\bDELETE\\s+FROM\\s+([\\w.\"`]+)", CI);
    private static final Pattern MERGE_INTO = Pattern.compile("\\bMERGE\\s+INTO\\s+([\\w.\"`]+)", CI);
    private static final Pattern USING = Pattern.compile("\\bUSING\\s+([\\w.\"`]+)", CI);

    private SqlLiterals() {
    }

    static boolean looksLikeSql(String text) {
        return text != null && IS_SQL.matcher(text).find();
    }

    /**
     * Adds the tables {@code sql} reads and writes to the given sets. The statement's
     * leading keyword decides how its clauses are read: {@code DELETE FROM t} writes
     * {@code t}, while {@code SELECT … FROM t} reads it — the same {@code FROM} means
     * different things, so the operation is classified first.
     */
    static void collect(String sql, Set<String> reads, Set<String> writes) {
        Matcher first = FIRST_KEYWORD.matcher(sql);
        if (!first.find()) {
            return;
        }
        String op = first.group(1).toUpperCase(Locale.ROOT);
        switch (op) {
            case "INSERT" -> {
                addAll(INSERT_INTO, sql, writes);
                // INSERT … SELECT … FROM other: the source is read.
                addAll(FROM, sql, reads);
                addAll(JOIN, sql, reads);
            }
            case "UPDATE" -> {
                addAll(UPDATE_TARGET, sql, writes);
                addAll(FROM, sql, reads);
                addAll(JOIN, sql, reads);
            }
            case "DELETE" -> {
                // The FROM here names the delete target, so it must not count as a read.
                addAll(DELETE_FROM, sql, writes);
                addAll(JOIN, sql, reads);
            }
            case "MERGE" -> {
                addAll(MERGE_INTO, sql, writes);
                addAll(USING, sql, reads);
            }
            default -> { // SELECT / WITH
                addAll(FROM, sql, reads);
                addAll(JOIN, sql, reads);
            }
        }
        reads.removeAll(writes); // a written table is not also reported as merely read
    }

    private static void addAll(Pattern p, String sql, Set<String> into) {
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String name = normalize(m.group(1));
            if (!name.isEmpty() && !isKeyword(name)) {
                into.add(name);
            }
        }
    }

    /** Lower-cases and strips quoting and any schema qualifier, so the name matches
     *  the table ids the schema and JPA rules use. */
    private static String normalize(String identifier) {
        String s = identifier.trim().replaceAll("[\"`]", "");
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Guards against reading something that is not a table name as one: a SQL keyword
     * that can follow the clause ("DELETE FROM ONLY t"), or an English article — an
     * ordinary sentence beginning "select …" would otherwise be read as a query and
     * invent a table from the word after "from".
     */
    private static boolean isKeyword(String word) {
        return switch (word) {
            case "select", "only", "lateral", "unnest", "table", "values", "dual" -> true;
            // Never a table name in real SQL, and the giveaway that a string is prose.
            case "the", "a", "an", "this", "that", "these", "those", "my", "our", "your",
                 "their", "its", "it", "here", "there" -> true;
            default -> false;
        };
    }
}
