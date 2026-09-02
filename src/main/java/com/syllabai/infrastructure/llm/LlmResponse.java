package com.syllabai.infrastructure.llm;

/**
 * Generation result with provenance for research records (§19: experiments must
 * record provider/model/version).
 */
public record LlmResponse(
        String text,
        String providerName,
        String model,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens) {
}
