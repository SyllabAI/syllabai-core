package com.syllabai.smartmark;

/**
 * Port for the candidate-generation stage of the Smart Mark pipeline (Master Spec §15).
 * Implementations propose a mark-point allocation for an answer; the proposal is a
 * candidate that must survive deterministic {@link MarkingValidator}s before any
 * marks are applied — the LLM is never the final truth.
 *
 * <p>Pattern: the pipeline behind this port composes Strategy validators; the LLM
 * adapter is one implementation (Adapter pattern over {@code LlmProvider}).</p>
 */
public interface MarkingCandidateGenerator {

    /**
     * Propose a mark allocation for the context's answer.
     *
     * @throws CandidateGenerationException when the generator cannot produce a
     *         usable candidate (provider unavailable, unparseable output). The
     *         pipeline records a failed run — it never fabricates marks.
     */
    MarkingCandidate propose(MarkingContext context);
}
