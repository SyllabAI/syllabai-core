package com.syllabai.infrastructure.llm;

/**
 * Signals a provider failure so the {@code FailoverLlmChain} can move to the next
 * provider (§26.1 automatic failover on error/rate-limit). Carries the structured
 * {@link LlmFailureClass} assigned at the provider/adapter boundary (ADR-023) —
 * the chain and the health snapshots consume the class instead of parsing messages.
 */
public class LlmProviderException extends RuntimeException {

    private final String providerName;
    private final LlmFailureClass failureClass;

    /** Legacy shape — classified {@link LlmFailureClass#UNKNOWN}. */
    public LlmProviderException(String providerName, String message, Throwable cause) {
        this(providerName, message, cause, LlmFailureClass.UNKNOWN);
    }

    public LlmProviderException(String providerName, String message, Throwable cause,
                                LlmFailureClass failureClass) {
        super(message, cause);
        this.providerName = providerName;
        this.failureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
    }

    public String providerName() {
        return providerName;
    }

    /** Structured failure classification (never null). */
    public LlmFailureClass failureClass() {
        return failureClass;
    }
}
