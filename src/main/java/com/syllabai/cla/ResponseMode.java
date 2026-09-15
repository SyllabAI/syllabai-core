package com.syllabai.cla;

/**
 * ResponseMode (CLA contract §3): explicit per request; the mode constrains
 * prompts and behavior deterministically — the mode is DATA in the pipeline
 * (recorded in evidence capture, rendered into the generation plan), never a
 * model judgment.
 *
 * <p>Step 1 serves EXPLAIN and SUMMARIZE only (contract §10.1). HINT and
 * CHECK are intentionally ABSENT until the attempt-aware answer-leakage gate
 * and its CI-mandatory negative suite exist (contract §7, §10.2) — declaring
 * them now would be an unsafe promotion of an unimplemented safety
 * property.</p>
 */
public enum ResponseMode {
    /** teach the anchored concept from validated material */
    EXPLAIN,
    /** compress the anchored resource, preserving provenance anchors */
    SUMMARIZE
}
