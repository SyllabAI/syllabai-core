package com.syllabai.cla;

/**
 * ResponseMode (CLA contract §3): explicit per request; the mode constrains
 * prompts and behavior deterministically — the mode is DATA in the pipeline
 * (recorded in evidence capture, rendered into the generation plan, and an
 * input to the §7 answer-leakage gate), never a model judgment.
 *
 * <p>Step 1 served EXPLAIN and SUMMARIZE (contract §10.1). Step 2 (contract
 * §10.2) adds HINT and CHECK on attempt-aware question contexts — both are
 * gated by the DETERMINISTIC leakage policy (contract §7.4: application code
 * over resolved ids and attempt state, never prompt-only):</p>
 *
 * <ul>
 *   <li>{@code HINT} — scaffolding only; mark-scheme evidence is excluded
 *       deterministically (§7.2), pre- or post-attempt;</li>
 *   <li>{@code CHECK} — full feedback, permitted ONLY after attempt evidence
 *       exists for the requesting learner and question (§7.3); pre-attempt
 *       CHECK is a deterministic 409, before any generation.</li>
 * </ul>
 */
public enum ResponseMode {
    /** teach the anchored concept from validated material */
    EXPLAIN,
    /** compress the anchored resource, preserving provenance anchors */
    SUMMARIZE,
    /** scaffold toward the learner's own next step — never the final answer */
    HINT,
    /** react to a submitted attempt — post-attempt only (contract §7.3) */
    CHECK
}
