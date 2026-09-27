package com.syllabai.tutor;

import java.util.List;

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
     * @param answer   the tutor's answer text (with [n] citation markers)
     * @param model    the model that produced it (experiment traceability, §26.1)
     * @param provider the provider that served it (groq/gemini/openrouter/…)
     */
    record GeneratedAnswer(String answer, String model, String provider) {
    }
}
