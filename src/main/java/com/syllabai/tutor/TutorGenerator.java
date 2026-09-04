package com.syllabai.tutor;

/**
 * Port: grounded tutor generation (Master Spec §13, T-024). Implementations
 * call the pinned free-LLM chain with a registered prompt version (§19) and
 * MUST enforce the grounding contract: answer only from the evidence in the
 * context, cite with [n] markers, refuse when evidence is insufficient.
 */
public interface TutorGenerator {

    /**
     * @param query   the learner's question
     * @param context the assembled grounded context
     * @return the generated answer + model identity for telemetry
     * @throws TutorGenerationException when no LLM provider is available
     */
    GeneratedAnswer generate(String query, ContextAssembler.TutorContext context);

    /**
     * @param answer   the tutor's answer text (with [n] citation markers)
     * @param model    the model that produced it (experiment traceability, §26.1)
     * @param provider the provider that served it (groq/gemini/openrouter/…)
     */
    record GeneratedAnswer(String answer, String model, String provider) {
    }
}
