package com.syllabai.infrastructure.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The §26.1 free-tier chain: Groq (primary) → Gemini 2.5 Flash (fallback) →
 * OpenRouter (tertiary). Implements {@link LlmProvider} itself, so callers depend
 * only on the port — the composite is transparent (Master Spec §23 composition).
 *
 * <p>Behaviour: iterate the chain in order; skip providers that are unconfigured or
 * in cooldown; on failure record health and continue; if every provider fails, throw.</p>
 *
 * <p>Per-experiment pinning (§26.1 provenance): when the request carries an
 * experiment id, the pin is resolved through the injected {@link ExperimentPinResolver}.
 * A pinned experiment is served <em>exclusively</em> by its pinned provider — there is
 * no failover, even when the pinned provider fails — and a request whose experiment
 * id resolves to no pin at all fails loudly with a clear message. Silent
 * provider/model drift mid-experiment would poison the research record.</p>
 */
public class FailoverLlmChain implements LlmProvider {

    private final Map<String, LlmProvider> providersByOrder;
    private final ExperimentPinResolver pinResolver;

    public FailoverLlmChain(List<LlmProvider> providersInOrder, ExperimentPinResolver pinResolver) {
        this.providersByOrder = new LinkedHashMap<>();
        for (LlmProvider provider : providersInOrder) {
            this.providersByOrder.put(provider.name(), provider);
        }
        this.pinResolver = pinResolver;
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
        LlmRequest effectiveRequest = request;
        List<LlmProvider> candidates;
        if (request.experimentId() != null && !request.experimentId().isBlank()) {
            ExperimentPin pin = resolvePin(request.experimentId());
            LlmProvider pinned = requirePinned(request.experimentId(), pin);
            if (pin.model() != null && !pin.model().isBlank() && request.model() == null) {
                effectiveRequest = request.withModel(pin.model());
            }
            candidates = List.of(pinned);
        } else {
            candidates = orderedAvailable();
        }
        if (candidates.isEmpty()) {
            throw new LlmProviderException("chain", "no available LLM provider in chain", null);
        }
        LlmProviderException last = null;
        for (LlmProvider provider : candidates) {
            try {
                return provider.generate(effectiveRequest);
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

    private ExperimentPin resolvePin(String experimentId) {
        return pinResolver.resolve(experimentId)
                .orElseThrow(() -> new LlmProviderException("chain",
                        "experiment '" + experimentId + "' is not pinned to any provider — refusing to "
                                + "generate to avoid silent provider drift. Pin it via "
                                + "syllabai.llm.experiment-pins or an experiments-registry row with "
                                + "status RUNNING (§26.1)",
                        null));
    }

    private LlmProvider requirePinned(String experimentId, ExperimentPin pin) {
        LlmProvider provider = providersByOrder.get(pin.provider());
        if (provider == null) {
            throw new LlmProviderException("chain",
                    "experiment '" + experimentId + "' is pinned to unknown provider '"
                            + pin.provider() + "' (registered providers: "
                            + providersByOrder.keySet() + ")",
                    null);
        }
        if (!provider.available()) {
            throw new LlmProviderException("chain",
                    "experiment '" + experimentId + "' is pinned to provider '" + pin.provider()
                            + "' which is currently unavailable — pinned experiments never fail "
                            + "over (§26.1)",
                    null);
        }
        return provider;
    }

    public java.util.Optional<LlmProvider> member(String name) {
        return java.util.Optional.ofNullable(providersByOrder.get(name));
    }
}
