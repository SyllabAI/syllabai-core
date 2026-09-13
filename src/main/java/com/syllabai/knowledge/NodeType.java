package com.syllabai.knowledge;

/**
 * Knowledge-graph node types (Master Spec §7 hierarchy).
 */
public enum NodeType {
    SUBJECT,
    UNIT,
    TOPIC,
    SUBTOPIC,
    MISCONCEPTION,
    /**
     * A concept node of the T-C11 settled concept graph (V15). Concepts are
     * NOT curriculum structure: they hang under SpecificationPoint SUBTOPICs
     * via SUGGESTED anchor PART_OF edges and carry the validated semantic
     * edges (prerequisites, remediation, …). Deliberately excluded from
     * {@code findStructureNodes()} (the KA-RAG intent surface) — the intent
     * matcher's contract is curriculum structure only.
     */
    CONCEPT
}
