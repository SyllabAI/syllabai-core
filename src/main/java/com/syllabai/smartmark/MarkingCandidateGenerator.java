package com.syllabai.smartmark;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Propose allocations for a BATCH of contexts (one attempt's answered parts).
     * The single-call implementation (v4 prompt) marks every part in ONE provider
     * round trip; the contract is otherwise identical to {@link #propose} applied
     * per context, and the marking rules are unchanged — only the transport
     * topology differs.
     *
     * <p>Default: the classic per-context topology (serial {@link #propose}), so
     * existing implementations (test fakes, lambdas) keep working unchanged. The
     * pipeline treats ANY exception from this method as a whole-batch failure and
     * falls back to per-context {@link #propose} runs — one flaky batch call can
     * never unmark parts the per-part path would have marked, and every part
     * keeps its independent failure semantics.</p>
     *
     * @param contexts the contexts to mark (same-scheme by construction: one
     *                 attempt's parts share the question version's scheme)
     * @return one candidate per context, index-aligned with the input
     * @throws CandidateGenerationException when the batch cannot be produced
     */
    default List<MarkingCandidate> proposeAll(List<MarkingContext> contexts) {
        List<MarkingCandidate> candidates = new ArrayList<>(contexts.size());
        for (MarkingContext context : contexts) {
            candidates.add(propose(context));
        }
        return candidates;
    }
}
