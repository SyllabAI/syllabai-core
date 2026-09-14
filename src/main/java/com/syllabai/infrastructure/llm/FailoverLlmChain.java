package com.syllabai.infrastructure.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The §26.1 free-tier chain: Groq (primary) → Gemini 2.5 Flash (fallback) →
 * OpenRouter (tertiary). Implements {@link LlmProvider} itself, so callers depend
 * only on the port — the composite is transparent (Master Spec §23 composition).
 *
 * <p>Behaviour: iterate the chain in order; skip providers that are unconfigured or
 * in cooldown; on failure record health and continue; if every provider fails, throw.</p>
 *
 * <p>Per-experiment pinning (§26.1 provenance, §19 reproducibility): when the request
 * carries an experiment id, the pin is resolved through the injected {@link ExperimentPinResolver}.
 * A pinned experiment is served <em>exclusively</em> by its pinned provider — there is
 * no failover, even when the pinned provider fails — and a request whose experiment
 * id resolves to no pin at all fails loudly with a clear message. Silent
 * provider/model drift mid-experiment would poison the research record.</p>
 *
 * <p>Model precedence for experiment requests is <strong>experiment pin &gt; caller
 * model &gt; provider default</strong>: when a pin names an exact model, that model is
 * used even if the caller supplied a different one — a caller must never be able to
 * silently override a registered experiment's model. When the pin names no model,
 * the caller's model (if any) applies, else the provider default. Ordinary
 * non-experiment requests are unaffected (caller model &gt; provider default).</p>
 */
public class FailoverLlmChain implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmChain.class);

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
            // §26.1 research pinning: experiment pin > caller model > provider default.
            // A pin that names a model always wins over a caller-supplied model — otherwise
            // any caller could silently drift a registered experiment off its model.
            if (pin.model() != null && !pin.model().isBlank()) {
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
        List<String> failures = new ArrayList<>();
        for (LlmProvider provider : candidates) {
            try {
                return provider.generate(effectiveRequest);
            } catch (LlmProviderException e) {
                failures.add(provider.name() + ": " + e.getMessage());
                last = e;
            }
        }
        // Diagnosability: the aggregate 503 must say WHICH provider failed and WHY,
        // otherwise an operator cannot distinguish a dead key (403) from a retired
        // model (404) from a quota outage (429) — the 2026-09-14 tutor outage was
        // invisible for exactly this reason (every cause swallowed to "generation
        // failed"). Provider error text contains no credential material.
        String detail = String.join(" | ", failures);
        log.warn("LLM chain exhausted ({} of {} providers attempted): {}",
                failures.size(), candidates.size(), detail);
        throw new LlmProviderException("chain",
                "all providers failed, last error: " + (last == null ? "unknown" : last.getMessage())
                        + " [" + detail + "]",
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
