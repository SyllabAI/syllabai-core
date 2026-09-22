package com.syllabai.infrastructure.llm;

/**
 * Generation result with provenance for research records (§19: experiments must
 * record provider/model/version).
 *
 * @param finishReason provider-reported completion stop cause (OpenAI-compatible
 *                     convention: {@code stop} / {@code length}; nullable — null
 *                     when the provider did not surface one). Callers that need
 *                     to distinguish a genuinely-finished completion from one cut
 *                     off by the token budget (reasoning models share that budget
 *                     with visible output) read this instead of guessing from the
 *                     text shape. Surface-only: the adapter never fails a call on
 *                     truncation — what truncation MEANS is the caller's decision.
 */
public record LlmResponse(
        String text,
        String providerName,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String finishReason) {

    /** Pre-finish-reason arity — delegates with a null finish reason. */
    public LlmResponse(String text, String providerName, String model,
                       long latencyMs, Integer promptTokens, Integer completionTokens) {
        this(text, providerName, model, latencyMs, promptTokens, completionTokens, null);
    }
}
