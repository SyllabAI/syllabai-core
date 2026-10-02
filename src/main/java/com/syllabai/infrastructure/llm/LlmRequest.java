package com.syllabai.infrastructure.llm;

/**
 * Generation request crossing the {@link LlmProvider} port.
 *
 * @param systemPrompt  persona/policy instructions (tutor prompt registry in Wave 3)
 * @param userPrompt    the actual user question
 * @param temperature   sampling temperature; null = provider default
 * @param maxTokens     response token cap; null = provider default
 * @param model         explicit model override; null = the provider's configured
 *                     default. For <em>experiment</em> requests a pin that names a
 *                     model always replaces this value (§26.1 precedence:
 *                     experiment pin &gt; caller model &gt; provider default);
 *                     without a pinned model this value is used as-is.
 * @param experimentId  when set, the chain must pin to the provider/model registered
 *                     for that experiment — no silent drift mid-experiment (§26.1);
 *                     unpinned experiment ids fail loudly
 * @param media         optional attached image (HUB-ANSWER-BOX wave 3 transcription);
 *                     null for pure-text requests. The chain routes media-carrying
 *                     requests ONLY to providers that declare media support — a
 *                     text-only member never sees one.
 * @param reasoningEffort optional reasoning-depth request for thinking models
 *                     (§26 knob): null = provider default (the pre-knob behavior).
 *                     Mapped per provider family inside the chain config's
 *                     runtime-options factories (OpenAI-compatible
 *                     {@code reasoning_effort}; Gemini {@code thinkingLevel}) —
 *                     non-thinking models ignore or reject it honestly.
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens,
        String model,
        String experimentId,
        LlmMedia media,
        LlmReasoningEffort reasoningEffort) {

    /** Legacy arity (pre-reasoning-effort) — media-free requests. */
    public LlmRequest(String systemPrompt, String userPrompt, Double temperature,
                      Integer maxTokens, String model, String experimentId) {
        this(systemPrompt, userPrompt, temperature, maxTokens, model, experimentId, null, null);
    }

    /** Legacy arity (pre-reasoning-effort) — with media, provider-default effort. */
    public LlmRequest(String systemPrompt, String userPrompt, Double temperature,
                      Integer maxTokens, String model, String experimentId, LlmMedia media) {
        this(systemPrompt, userPrompt, temperature, maxTokens, model, experimentId, media, null);
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, null, null, null, null, null, null);
    }

    public static LlmRequest withOptions(String systemPrompt, String userPrompt,
                                          Double temperature, Integer maxTokens) {
        return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens, null, null,
                null, null);
    }

    /** Copy with an explicit model (per-experiment model pinning, §26.1). */
    public LlmRequest withModel(String pinnedModel) {
        return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens,
                pinnedModel, experimentId, media, reasoningEffort);
    }

    /** Copy with attached media (transcription path); null clears it. */
    public LlmRequest withMedia(LlmMedia attachedMedia) {
        return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens,
                model, experimentId, attachedMedia, reasoningEffort);
    }

    /** Copy with a reasoning-effort request; null clears it back to provider default. */
    public LlmRequest withReasoningEffort(LlmReasoningEffort effort) {
        return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens,
                model, experimentId, media, effort);
    }

    /** Whether this request carries image content the provider must read. */
    public boolean hasMedia() {
        return media != null;
    }
}
