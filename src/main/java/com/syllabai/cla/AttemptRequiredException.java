package com.syllabai.cla;

/**
 * Deterministic answer-leakage refusal (CLA contract §7.3): CHECK was
 * requested on a question context whose attempt evidence does not exist for
 * the requesting learner. The gate is application code over resolved ids and
 * attempt state (§7.4) — this exception is thrown BEFORE any retrieval or
 * generation, so a pre-attempt CHECK can never reach question content, a
 * provider, or evidence assembly.
 */
public class AttemptRequiredException extends RuntimeException {

    public AttemptRequiredException() {
        super("full feedback requires an attempt on this question first — "
                + "the answer-leakage gate unlocks CHECK after attempt evidence exists");
    }
}
