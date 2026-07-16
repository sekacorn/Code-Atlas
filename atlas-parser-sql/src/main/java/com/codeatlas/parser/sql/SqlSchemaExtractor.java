package com.codeatlas.parser.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads DDL with a deterministic statement scanner. Nothing is executed and no SQL
 * engine is involved: only what the file literally declares is recorded.
 *
 * <p>Extracted: {@code CREATE TABLE} (with its columns, primary keys and inline or
 * table-level foreign keys), {@code CREATE VIEW}, and {@code ALTER TABLE … ADD …
 * FOREIGN KEY}.
 *
 * <p>Limits (honest by design): this is not a SQL grammar. Dynamic SQL, stored
 * procedures, triggers, vendor-specific extensions, partitioning and a view's
 * underlying query are not analysed; a view is recorded as an object, not as a
 * query over other tables. Identifiers are compared case-insensitively (SQL's own
 * rule) and quoting is stripped.
 */
final class SqlSchemaExtractor {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "\\bCREATE\\s+(?:GLOBAL\\s+|LOCAL\\s+|TEMPORARY\\s+|TEMP\\s+)*TABLE\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)\\s*\\((.*)\\)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+"
                    + "(?:IF\\s+NOT\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)\\s+AS\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER_TABLE_FK = Pattern.compile(
            "\\bALTER\\s+TABLE\\s+(?:ONLY\\s+)?([\\w.\"`\\[\\]]+)\\s+ADD\\s+"
                    + "(?:CONSTRAINT\\s+[\\w.\"`\\[\\]]+\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*"
                    + "REFERENCES\\s+([\\w.\"`\\[\\]]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE_FK = Pattern.compile(
            "^\\s*(?:CONSTRAINT\\s+[\\w.\"`\\[\\]]+\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*"
                    + "REFERENCES\\s+([\\w.\"`\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_FK = Pattern.compile(
            "\\bREFERENCES\\s+([\\w.\"`\\[\\]]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_PK = Pattern.compile(
            "^\\s*(?:CONSTRAINT\\s+[\\w.\"`\\[\\]]+\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE);
    /** A table-level constraint rather than a column definition. */
    private static final Pattern CONSTRAINT_ITEM = Pattern.compile(
            "^\\s*(CONSTRAINT|PRIMARY|FOREIGN|UNIQUE|CHECK|INDEX|KEY|EXCLUDE)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN = Pattern.compile(
            "^\\s*([\\w\"`\\[\\]]+)\\s+([\\w]+(?:\\s*\\([^)]*\\))?)", Pattern.CASE_INSENSITIVE);

    private SqlSchemaExtractor() {
    }

    /** One declared column. */
    record Column(String name, String type, boolean primaryKey, boolean notNull) {
    }

    /** A foreign key from the declaring table to another table. */
    record ForeignKey(String fromColumn, String toTable, int line) {
    }

    /** A declared table or view. */
    record Table(String name, String declaredName, boolean view, List<Column> columns,
                 List<ForeignKey> foreignKeys, int line) {
    }

    /** Everything a DDL file declares. */
    record Schema(List<Table> tables, List<ForeignKey> alterForeignKeys, List<String> alterTargets) {
    }

    static Schema parse(String content) {
        String sql = stripComments(content);
        List<Table> tables = new ArrayList<>();
        List<ForeignKey> alterFks = new ArrayList<>();
        List<String> alterTargets = new ArrayList<>();

        for (Statement st : statements(sql)) {
            Matcher table = CREATE_TABLE.matcher(st.text);
            if (table.find()) {
                tables.add(table(normalize(table.group(1)), rawName(table.group(1)),
                        table.group(2), st.line));
                continue;
            }
            Matcher view = CREATE_VIEW.matcher(st.text);
            if (view.find()) {
                tables.add(new Table(normalize(view.group(1)), rawName(view.group(1)), true,
                        List.of(), List.of(), st.line));
                continue;
            }
            Matcher alter = ALTER_TABLE_FK.matcher(st.text);
            if (alter.find()) {
                alterTargets.add(normalize(alter.group(1)));
                alterFks.add(new ForeignKey(normalize(firstColumn(alter.group(2))),
                        normalize(alter.group(3)), st.line));
            }
        }
        return new Schema(List.copyOf(tables), List.copyOf(alterFks), List.copyOf(alterTargets));
    }

    private static Table table(String name, String declaredName, String body, int line) {
        List<Column> columns = new ArrayList<>();
        List<ForeignKey> fks = new ArrayList<>();
        List<String> pkColumns = new ArrayList<>();

        for (String item : splitTopLevel(body)) {
            Matcher tableFk = TABLE_FK.matcher(item);
            if (tableFk.find()) {
                fks.add(new ForeignKey(normalize(firstColumn(tableFk.group(1))),
                        normalize(tableFk.group(2)), line));
                continue;
            }
            Matcher tablePk = TABLE_PK.matcher(item);
            if (tablePk.find()) {
                for (String c : tablePk.group(1).split(",")) {
                    pkColumns.add(normalize(c));
                }
                continue;
            }
            if (CONSTRAINT_ITEM.matcher(item).find()) {
                continue; // some other table-level constraint
            }
            Matcher col = COLUMN.matcher(item);
            if (col.find()) {
                String colName = normalize(col.group(1));
                boolean inlinePk = item.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
                boolean notNull = item.toUpperCase(Locale.ROOT).contains("NOT NULL");
                columns.add(new Column(colName, col.group(2).replaceAll("\\s+", ""), inlinePk, notNull));
                Matcher inlineFk = INLINE_FK.matcher(item);
                if (inlineFk.find()) {
                    fks.add(new ForeignKey(colName, normalize(inlineFk.group(1)), line));
                }
            }
        }
        // Fold table-level PRIMARY KEY(...) back onto the columns it names.
        List<Column> withPk = new ArrayList<>();
        for (Column c : columns) {
            withPk.add(pkColumns.contains(c.name())
                    ? new Column(c.name(), c.type(), true, c.notNull()) : c);
        }
        return new Table(name, declaredName, false, List.copyOf(withPk), List.copyOf(fks), line);
    }

    // ---- lexing helpers ----

    private record Statement(String text, int line) {
    }

    /** Splits on top-level semicolons, remembering each statement's starting line. */
    private static List<Statement> statements(String sql) {
        List<Statement> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int line = 1;
        int startLine = 1;
        int depth = 0;
        boolean started = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\n') {
                line++;
            }
            if (!started && !Character.isWhitespace(c)) {
                started = true;
                startLine = line;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (c == ';' && depth == 0) {
                if (!current.toString().isBlank()) {
                    out.add(new Statement(current.toString().trim(), startLine));
                }
                current.setLength(0);
                started = false;
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            out.add(new Statement(current.toString().trim(), startLine));
        }
        return out;
    }

    /** Splits a CREATE TABLE body on commas that are not inside parentheses. */
    private static List<String> splitTopLevel(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(item.toString());
                item.setLength(0);
                continue;
            }
            item.append(c);
        }
        if (!item.toString().isBlank()) {
            out.add(item.toString());
        }
        return out;
    }

    /** Removes "--" line comments and block comments, preserving line structure. */
    private static String stripComments(String content) {
        StringBuilder out = new StringBuilder(content.length());
        boolean inBlock = false;
        for (String raw : content.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                if (inBlock) {
                    if (raw.startsWith("*/", i)) {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (raw.startsWith("/*", i)) {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (raw.startsWith("--", i)) {
                    break;
                }
                line.append(raw.charAt(i));
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String firstColumn(String columnList) {
        String[] parts = columnList.split(",");
        return parts.length > 0 ? parts[0] : columnList;
    }

    /** Strips quoting and any schema qualifier, and lower-cases (SQL identifiers are
     *  case-insensitive, and the JPA side names tables in lower case). */
    private static String normalize(String identifier) {
        String s = rawName(identifier);
        return s.toLowerCase(Locale.ROOT);
    }

    /** Strips quoting and any schema/catalog qualifier, keeping the declared spelling. */
    private static String rawName(String identifier) {
        String s = identifier.trim().replaceAll("[\"`\\[\\]]", "");
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
