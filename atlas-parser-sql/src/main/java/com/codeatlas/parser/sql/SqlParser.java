package com.codeatlas.parser.sql;

import com.codeatlas.model.Entity;
import com.codeatlas.model.EntityKind;
import com.codeatlas.model.EvidenceKeys;
import com.codeatlas.model.Relationship;
import com.codeatlas.model.RelationshipKind;
import com.codeatlas.model.ResolutionStatus;
import com.codeatlas.model.SourceLocation;
import com.codeatlas.parser.api.ParseIssue;
import com.codeatlas.parser.api.ParseRequest;
import com.codeatlas.parser.api.ParseResult;
import com.codeatlas.parser.api.RepositoryParser;

/**
 * Parses SQL/DDL into the schema the repository <em>declares</em>: tables and views
 * with their columns and foreign keys.
 *
 * <p>Why this matters: without it, a table only exists because a JPA {@code @Entity}
 * implied one, and its name is a documented <em>inference</em> from naming
 * conventions. A parsed {@code CREATE TABLE} is a declaration with a file and line
 * behind it. Because a table's stable id is its name ({@code sql:table:customer}),
 * a declared table and the table a JPA entity maps to are the <em>same</em> entity —
 * the model merges them, and the mapping becomes confirmed against a real schema.
 *
 * <p>A table a JPA entity maps to that no DDL declares stays visible as a table
 * without a declaration, which is an honest gap rather than a silent assumption.
 *
 * <p>Nothing is executed and no database is contacted.
 */
public final class SqlParser implements RepositoryParser {

    static final String LANGUAGE = "sql";
    static final String VERSION = "1.0.0";

    /** Marks a table/view this parser saw declared in DDL (vs. inferred from code). */
    public static final String DECLARED_IN = "declaredIn";

    @Override
    public String languageId() {
        return LANGUAGE;
    }

    @Override
    public String displayName() {
        return "SQL / DDL (schema)";
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    @Override
    public boolean supports(ParseRequest request) {
        return switch (request.extension()) {
            case "sql", "ddl" -> true;
            default -> false;
        };
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String file = request.relativePath();
        ParseResult.Builder out = ParseResult.builder(LANGUAGE, file);
        try {
            SqlSchemaExtractor.Schema schema = SqlSchemaExtractor.parse(request.content());
            for (SqlSchemaExtractor.Table t : schema.tables()) {
                emitTable(out, t, file);
            }
            // ALTER TABLE … ADD FOREIGN KEY may target a table declared elsewhere, so
            // the edge is emitted from the named table's deterministic id.
            for (int i = 0; i < schema.alterForeignKeys().size(); i++) {
                SqlSchemaExtractor.ForeignKey fk = schema.alterForeignKeys().get(i);
                String owner = schema.alterTargets().get(i);
                emitForeignKey(out, tableId(owner), fk, file);
            }
            if (schema.tables().isEmpty() && schema.alterForeignKeys().isEmpty()) {
                out.issue(ParseIssue.warning(
                        "no CREATE TABLE/VIEW or ALTER TABLE … FOREIGN KEY found; nothing recorded", 1));
            }
        } catch (RuntimeException e) {
            out.issue(ParseIssue.error("SQL could not be parsed: " + e.getMessage(), 0));
        }
        return out.build();
    }

    private void emitTable(ParseResult.Builder out, SqlSchemaExtractor.Table t, String file) {
        SourceLocation loc = new SourceLocation(file, t.line(), t.line(), 0, 0);
        Entity table = Entity.builder(EntityKind.DATABASE_OBJECT, t.name())
                .qualifiedName(t.name())
                .language(LANGUAGE)
                .location(loc)
                .attribute(Entity.Attributes.DB_OBJECT_TYPE, t.view() ? "view" : "table")
                .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true)
                .attribute(DECLARED_IN, file + ":" + t.line())
                .attribute("declaredName", t.declaredName())
                .attribute("columnCount", t.columns().size())
                .build();
        out.entity(table);
        out.relationship(Relationship.builder(RelationshipKind.CONTAINS, "file:" + file, table.id()).build());

        for (SqlSchemaExtractor.Column c : t.columns()) {
            Entity column = Entity.builder(EntityKind.FIELD, c.name())
                    .qualifiedName(t.name() + "." + c.name())
                    .language(LANGUAGE)
                    .location(loc)
                    .attribute("sqlType", c.type())
                    .attribute("primaryKey", c.primaryKey())
                    .attribute("notNull", c.notNull())
                    .attribute(Entity.Attributes.EXTERNALLY_EXPOSED, true)
                    .build();
            out.entity(column);
            out.relationship(Relationship.builder(RelationshipKind.CONTAINS, table.id(), column.id()).build());
        }
        for (SqlSchemaExtractor.ForeignKey fk : t.foreignKeys()) {
            emitForeignKey(out, table.id(), fk, file);
        }
    }

    /**
     * A foreign key is a reference from one table to another. It is emitted
     * unresolved with the referenced table's name, so the Linker connects it only
     * when that table really exists in this repository — a key pointing at a table
     * no file declares stays an honest unresolved reference.
     */
    private void emitForeignKey(ParseResult.Builder out, String fromTableId,
                                SqlSchemaExtractor.ForeignKey fk, String file) {
        out.relationship(Relationship.builder(RelationshipKind.REFERENCES, fromTableId, fk.toTable())
                .resolved(false)
                .status(ResolutionStatus.DISCOVERED)
                .location(new SourceLocation(file, fk.line(), fk.line(), 0, 0))
                .attribute(EvidenceKeys.TYPE_NAME, fk.toTable())
                .attribute(EvidenceKeys.RULE_ID, "ATLAS-SQL-FOREIGN-KEY-001")
                .attribute(EvidenceKeys.ANALYZER_ID, LANGUAGE + "/" + VERSION)
                .attribute(EvidenceKeys.CONFIDENCE, "1.00")
                .attribute("foreignKeyColumn", fk.fromColumn())
                .build());
    }

    /** The deterministic id of a table, matching the id the lineage rules use. */
    private static String tableId(String tableName) {
        return Entity.stableId(LANGUAGE, EntityKind.DATABASE_OBJECT, tableName);
    }
}
