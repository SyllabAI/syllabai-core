package com.syllabai.infrastructure.llm;

import java.util.List;

/**
 * Port for chat-completion providers (Master Spec §26 — "Never place provider-specific
 * API calls inside domain services"). Implementations adapt concrete SDKs/HTTP clients
 * behind this interface (§23 Adapter pattern).
 */
public interface LlmProvider {

    /** Stable provider identifier, e.g. "groq", "gemini", "openrouter", "chain". */
    String name();

    /** Whether this provider is currently usable (configured and not in cooldown). */
    boolean available();

    /**
     * Generate a completion.
     *
     * @throws LlmProviderException when the provider fails — callers (the chain)
     *         treat this as a failover signal
     */
    LlmResponse generate(LlmRequest request);

    /** Health/rate-budget snapshot for observability (§32). */
    LlmProviderHealth health();
}
