package com.syllabai.infrastructure.llm;

/**
 * One incremental piece of a streamed completion (tutor SSE tranche). Every
 * delta carries the provider identity that produced it, so downstream
 * consumers (the tutor's {@code meta} event, telemetry) can attribute the
 * stream the moment the FIRST token lands — before that, no byte of
 * generation has been committed to the client and failover is still free.
 *
 * @param text         the increment (never null; possibly empty, which
 *                     callers filter)
 * @param providerName the provider serving this stream (groq/gemini/…)
 * @param model        the model serving this stream (request/pin override
 *                     already applied)
 */
public record LlmDelta(String text, String providerName, String model) {

    public static LlmDelta of(LlmResponse response) {
        return new LlmDelta(response.text(), response.providerName(), response.model());
    }
}
