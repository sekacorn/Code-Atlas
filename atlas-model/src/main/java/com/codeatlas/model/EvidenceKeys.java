package com.codeatlas.model;

/**
 * Well-known relationship attribute keys that carry a lineage edge's evidence
 * metadata. Defined once so parsers, analyzers and reporting agree on spelling.
 *
 * <p>Every lineage edge carries: which rule produced it (and the rule version),
 * which component ran the rule (and its version), a rule-derived confidence in
 * [0,1], and whether the edge is inferred rather than resolved. The edge's
 * {@link Relationship#status()} and optional {@link Relationship#location()}
 * complete the evidence.
 */
public final class EvidenceKeys {

    /** Stable rule identifier, e.g. {@code ATLAS-LINEAGE-ENDPOINT-001}. */
    public static final String RULE_ID = "ruleId";
    /** Version of the rule's logic. */
    public static final String RULE_VERSION = "ruleVersion";
    /** Component that produced the edge, e.g. {@code java/1.1.0} or {@code lineage-analyzer/1.0.0}. */
    public static final String ANALYZER_ID = "analyzerId";
    /** Rule-derived confidence in [0,1], rendered with two decimals. */
    public static final String CONFIDENCE = "confidence";
    /** "true" when the edge is a convention/naming inference, not a resolved fact. */
    public static final String INFERRED = "inferred";
    /** "true" when several candidates exist and none could be chosen safely. */
    public static final String AMBIGUOUS = "ambiguous";

    // Parser-supplied call metadata (consumed by the lineage analyzer).
    public static final String CALL_NAME = "callName";
    public static final String ARG_COUNT = "argCount";
    public static final String RECEIVER_NAME = "receiverName";
    public static final String TYPE_NAME = "typeName";

    private EvidenceKeys() {
    }
}
