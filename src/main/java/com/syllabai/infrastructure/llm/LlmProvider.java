package com.syllabai.infrastructure.llm;

import java.util.List;
import reactor.core.publisher.Flux;

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
     * Whether this provider can accept image content ({@link LlmRequest#media()}).
     * The chain routes media-carrying requests only to providers that answer true
     * here — a text-only member never receives one to fail on (HUB-ANSWER-BOX
     * wave 3). Default false: existing and future text-only implementations are
     * safe without touching this interface.
     */
    default boolean supportsMedia() {
        return false;
    }

    /**
     * Generate a completion.
     *
     * @throws LlmProviderException when the provider fails — callers (the chain)
     *         treat this as a failover signal
     */
    LlmResponse generate(LlmRequest request);

    /**
     * Stream a completion as incremental {@link LlmDelta}s. The default
     * implementation degrades gracefully to a single-delta stream over
     * {@link #generate} — providers that do not implement token streaming (and
     * any future port implementation) still satisfy the streaming contract, at
     * first-token latency equal to the full blocking call. Failover semantics
     * are the chain's concern (see {@link FailoverLlmChain#stream}); a provider
     * implementation reports its own failures through the Flux's error signal
     * with the same {@link LlmProviderException} contract as {@link #generate}.
     */
    default Flux<LlmDelta> stream(LlmRequest request) {
        return Flux.defer(() -> Flux.just(LlmDelta.of(generate(request))));
    }

    /** Health/rate-budget snapshot for observability (§32). */
    LlmProviderHealth health();
}
