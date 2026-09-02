package com.syllabai.infrastructure.llm;

/**
 * Generation request crossing the {@link LlmProvider} port.
 *
 * @param systemPrompt  persona/policy instructions (tutor prompt registry in Wave 3)
 * @param userPrompt    the actual user question
 * @param temperature   sampling temperature; null = provider default
 * @param maxTokens     response token cap; null = provider default
 * @param experimentId  when set, the chain must pin to the provider/model registered
 *                     for that experiment — no silent drift mid-experiment (§26.1)
 */
public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens,
        String experimentId) {

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, null, null, null);
    }

    public static LlmRequest withOptions(String systemPrompt, String userPrompt,
                                          Double temperature, Integer maxTokens) {
        return new LlmRequest(systemPrompt, userPrompt, temperature, maxTokens, null);
    }
}
