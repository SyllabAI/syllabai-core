package com.syllabai.tutor;

import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Port: grounded tutor generation (Master Spec §13, T-024). Implementations
 * call the pinned free-LLM chain with a registered prompt version (§19) and
 * MUST enforce the grounding contract: answer only from the evidence in the
 * context, cite with [n] markers, refuse when evidence is insufficient.
 */
public interface TutorGenerator {

    /**
     * Single-turn ask (anchored surfaces: CLA, paper-question previews).
     * Equivalent to {@code generate(query, List.of(), context)}.
     *
     * @param query   the learner's question
     * @param context the assembled grounded context
     * @return the generated answer + model identity for telemetry
     * @throws TutorGenerationException when no LLM provider is available
     */
    GeneratedAnswer generate(String query, ContextAssembler.TutorContext context);

    /**
     * Conversational ask (s139 working memory): the final QUESTION plus the
     * sanitized prior turns of the same chat. History is context for
     * reference resolution ("it", "the second point") — never extra evidence;
     * the grounding contract still binds to the SOURCES in this context only.
     *
     * @param query   the learner's question (the turn to answer now)
     * @param history prior turns, oldest first, already sanitized
     * @param context the assembled grounded context for THIS ask
     * @throws TutorGenerationException when no LLM provider is available
     */
    default GeneratedAnswer generate(String query, List<ConversationTurn> history,
                                     ContextAssembler.TutorContext context) {
        return generate(query, context);
    }

    /**
     * Streamed conversational ask (tutor SSE tranche): the same prompt
     * assembly and grounding contract as {@link #generate(String, List,
     * ContextAssembler.TutorContext)}, delivered as incremental deltas.
     *
     * <p>Contract (parity with the blocking path is REQUIRED, not optional):</p>
     * <ul>
     *   <li>the CONCATENATION of all emitted deltas equals what the blocking
     *       path would serve for the same generation — output hygiene
     *       (fence-echo and out-of-range-marker stripping) applies
     *       incrementally, not only at the end;</li>
     *   <li>every delta carries the provider/model identity that produced it
     *       (null on tail-flush deltas — consumers take the identity from the
     *       FIRST delta);</li>
     *   <li>an unavailable chain or generation failure surfaces as a Flux
     *       error carrying {@link TutorGenerationException} whose message is
     *       the fixed client-safe text (deep-audit M2 contract).</li>
     * </ul>
     *
     * <p>The default implementation degrades to a single-delta stream over
     * {@link #generate(String, List, ContextAssembler.TutorContext)} — a
     * generator without streaming support still satisfies the contract.</p>
     */
    default Flux<GeneratedDelta> streamGenerate(String query, List<ConversationTurn> history,
                                                ContextAssembler.TutorContext context) {
        return Flux.defer(() -> {
            GeneratedAnswer answer = generate(query, history, context);
            return answer == null ? Flux.empty()
                    : Flux.just(new GeneratedDelta(answer.answer(), answer.model(), answer.provider()));
        });
    }

    /**
     * @param answer   the tutor's answer text (with [n] citation markers)
     * @param model    the model that produced it (experiment traceability, §26.1)
     * @param provider the provider that served it (groq/gemini/openrouter/…)
     */
    record GeneratedAnswer(String answer, String model, String provider) {
    }

    /**
     * One incremental piece of a streamed answer. {@code model}/{@code
     * provider} ride every generation delta (identity is committed with the
     * first token); they are null on the sanitizer's tail-flush delta, which
     * is our own held-back text, not a provider emission.
     */
    record GeneratedDelta(String answer, String model, String provider) {
    }
}
