package com.codeatlas.analysis.lineage;

/**
 * The deterministic rule catalog for Java data lineage. Every lineage edge names
 * the rule that produced it; confidence values are fixed per rule (never model-
 * generated) and documented in DATA_LINEAGE.md.
 *
 * <p>Confidence bands: 1.00 direct annotation; 0.90–0.99 resolved symbol chain
 * with minor framework assumptions; 0.70–0.89 multiple structural signals;
 * 0.40–0.69 naming/convention inference; below 0.40 is not emitted.
 */
public final class LineageRules {

    public static final String ANALYZER_ID = "lineage-analyzer/1.0.0";
    public static final String RULE_VERSION = "1";

    /** HTTP endpoint discovered from a mapping annotation (emitted by the Java parser). */
    public static final String ENDPOINT = "ATLAS-LINEAGE-ENDPOINT-001";
    /** Endpoint request/response DTO connection via declared parameter/return types. */
    public static final String ENDPOINT_IO = "ATLAS-LINEAGE-ENDPOINT-002";
    /** Method call resolved through a declared field/receiver type or same-class lookup. */
    public static final String CALL = "ATLAS-LINEAGE-CALL-001";
    /** Injection-point call into an interface with exactly one known implementation. */
    public static final String DI_UNIQUE = "ATLAS-LINEAGE-DI-001";
    /** Injection-point call into an interface with several implementations (kept ambiguous). */
    public static final String DI_AMBIGUOUS = "ATLAS-LINEAGE-DI-002";
    /** Transformation detected from type flow plus instantiation of the target type. */
    public static final String MAP_TYPEFLOW = "ATLAS-LINEAGE-MAP-001";
    /** Transformation suggested by naming convention only (inferred). */
    public static final String MAP_NAMING = "ATLAS-LINEAGE-MAP-002";
    /** DTO validated by an explicit validator call resolved through the call graph. */
    public static final String VALIDATION = "ATLAS-LINEAGE-VALIDATION-001";
    /** JPA entity mapped to an explicit @Table name. */
    public static final String JPA_TABLE_EXPLICIT = "ATLAS-LINEAGE-JPA-TABLE-001";
    /** JPA entity mapped to a default-named table (naming-strategy dependent, inferred). */
    public static final String JPA_TABLE_DEFAULT = "ATLAS-LINEAGE-JPA-TABLE-002";
    /** Spring Data repository manages the entity in its first type argument. */
    public static final String REPOSITORY_MANAGES = "ATLAS-LINEAGE-REPOSITORY-001";
    /** Repository persists to the table its managed entity maps to. */
    public static final String REPOSITORY_TABLE = "ATLAS-LINEAGE-REPOSITORY-002";
    /** Read-classified repository operation (find, get, read, query, count, exists prefixes). */
    public static final String READ = "ATLAS-LINEAGE-READ-001";
    /** Write-classified repository operation (save, insert, update, delete prefixes). */
    public static final String WRITE = "ATLAS-LINEAGE-WRITE-001";
    /** Repository operation whose read/write intent could not be classified. */
    public static final String REPOSITORY_TOUCH = "ATLAS-LINEAGE-REPOSITORY-003";
    /** A detected reference whose target could not be identified — kept, never dropped. */
    public static final String UNRESOLVED = "ATLAS-LINEAGE-UNRESOLVED-001";
    /** Table touched by a literal SQL statement (JDBC or {@code @Query}) in a body. */
    public static final String SQL_LITERAL = "ATLAS-LINEAGE-SQL-001";
    /** Table named by a literal fragment of SQL that is assembled at runtime (inferred). */
    public static final String SQL_DYNAMIC = "ATLAS-LINEAGE-SQL-002";

    // ---- Ada rules ----

    /** Ada call resolved by qualified name (0.95) or unique simple name (0.85). */
    public static final String ADA_CALL = "ATLAS-LINEAGE-ADA-CALL-001";
    /** Ada call with several candidate subprograms (overloads) — all kept, ambiguous. */
    public static final String ADA_CALL_AMBIGUOUS = "ATLAS-LINEAGE-ADA-CALL-002";
    /** Ada transformation: single-parameter function between two project types, mapper-style name. */
    public static final String ADA_MAP = "ATLAS-LINEAGE-ADA-MAP-001";
    /** Ada transformation suggested by type flow alone (inferred). */
    public static final String ADA_MAP_TYPEFLOW = "ATLAS-LINEAGE-ADA-MAP-002";
    /** Assignment to package state (qualified 0.90, enclosing-package 0.85). */
    public static final String ADA_WRITE = "ATLAS-LINEAGE-ADA-WRITE-001";
    /** Read of package state (qualified 0.90, enclosing-package 0.85). */
    public static final String ADA_READ = "ATLAS-LINEAGE-ADA-READ-001";
    /** Console input via Ada.Text_IO (with-clause + Get/Get_Line/Get_Immediate). */
    public static final String ADA_IO_READ = "ATLAS-LINEAGE-ADA-IO-001";
    /** Console output via Ada.Text_IO (with-clause + Put/Put_Line). */
    public static final String ADA_IO_WRITE = "ATLAS-LINEAGE-ADA-IO-002";
    /** Qualified call into a withed unit that is not part of the analyzed code. */
    public static final String ADA_EXTERNAL = "ATLAS-LINEAGE-ADA-EXTERNAL-001";

    private LineageRules() {
    }
}
