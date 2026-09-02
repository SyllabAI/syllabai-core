package com.syllabai.infrastructure.llm;

/**
 * Signals a provider failure so the {@code FailoverLlmChain} can move to the next
 * provider (§26.1 automatic failover on error/rate-limit).
 */
public class LlmProviderException extends RuntimeException {

    private final String providerName;

    public LlmProviderException(String providerName, String message, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
    }

    public String providerName() {
        return providerName;
    }
}
