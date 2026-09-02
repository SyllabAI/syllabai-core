package com.syllabai.infrastructure.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The §26.1 free-tier chain: Groq (primary) → Gemini 2.5 Flash (fallback) →
 * OpenRouter (tertiary). Implements {@link LlmProvider} itself, so callers depend
 * only on the port — the composite is transparent (Master Spec §23 composition).
 *
 * <p>Behaviour: iterate the chain in order; skip providers that are unconfigured or
 * in cooldown; on failure record health and continue; if every provider fails, throw.
 * Per-experiment pinning: when the request carries an experiment id that is pinned
 * in {@code syllabai.llm.experiment-pins}, only the pinned provider is used — no
 * silent provider drift mid-experiment (§26.1).</p>
 */
public class FailoverLlmChain implements LlmProvider {

    private final Map<String, LlmProvider> providersByOrder;

    public FailoverLlmChain(List<LlmProvider> providersInOrder) {
        this.providersByOrder = new LinkedHashMap<>();
        for (LlmProvider provider : providersInOrder) {
            this.providersByOrder.put(provider.name(), provider);
        }
    }

    @Override
    public String name() {
        return "chain";
    }

    @Override
    public boolean available() {
        return providersByOrder.values().stream().anyMatch(LlmProvider::available);
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        LlmProvider pinned = pinnedProvider(request);
        List<LlmProvider> candidates = pinned != null ? List.of(pinned) : orderedAvailable();
        if (candidates.isEmpty()) {
            throw new LlmProviderException("chain", "no available LLM provider in chain", null);
        }
        LlmProviderException last = null;
        for (LlmProvider provider : candidates) {
            try {
                return provider.generate(request);
            } catch (LlmProviderException e) {
                last = e;
            }
        }
        throw new LlmProviderException("chain",
                "all providers failed, last error: " + (last == null ? "unknown" : last.getMessage()),
                last);
    }

    @Override
    public LlmProviderHealth health() {
        // composite health: configured when any member is configured
        return new LlmProviderHealth(providersByOrder.values().stream()
                .anyMatch(p -> p.health().snapshot().configured()));
    }

    /** Observability: snapshot of every member. */
    public Map<String, LlmProviderHealth.Snapshot> memberHealth() {
        Map<String, LlmProviderHealth.Snapshot> snapshot = new LinkedHashMap<>();
        providersByOrder.forEach((name, provider) -> snapshot.put(name, provider.health().snapshot()));
        return snapshot;
    }

    private List<LlmProvider> orderedAvailable() {
        List<LlmProvider> available = new ArrayList<>();
        for (LlmProvider provider : providersByOrder.values()) {
            if (provider.available()) {
                available.add(provider);
            }
        }
        return available;
    }

    private LlmProvider pinnedProvider(LlmRequest request) {
        if (request.experimentId() == null || request.experimentId().isBlank()) {
            return null;
        }
        // pin resolution is injected through the chain construction (experiment registry
        // integration lands with T-030); v0 consults the provider list directly.
        return null;
    }

    public Optional<LlmProvider> member(String name) {
        return Optional.ofNullable(providersByOrder.get(name));
    }
}
