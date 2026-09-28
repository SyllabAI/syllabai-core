package com.syllabai.infrastructure.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * The §26.1 free-tier chain: Groq (primary) → Gemini 2.5 Flash (fallback) →
 * OpenRouter (tertiary). Implements {@link LlmProvider} itself, so callers depend
 * only on the port — the composite is transparent (Master Spec §23 composition).
 *
 * <p>Behaviour: iterate the chain in order; skip providers that are unconfigured or
 * in cooldown; on failure record health and continue; if every provider fails, throw.</p>
 *
 * <p>Media routing (HUB-ANSWER-BOX wave 3): a request carrying {@link LlmMedia}
 * is offered ONLY to members that declare {@link LlmProvider#supportsMedia()} —
 * a text-only provider never receives an image it would merely fail on. When no
 * vision-capable member is available the request fails with an explicit message
 * (503 at the web layer) instead of degrading into a text-only hallucination of
 * an image the provider never saw. The filter lives in the shared routing step,
 * so the blocking and streaming paths cannot drift apart on it.</p>
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
        Routing routing = routingOf(request);
        if (routing.candidates().isEmpty()) {
            throw new LlmProviderException("chain", emptyChainMessage(request), null);
        }
        LlmRequest effectiveRequest = routing.request();
        List<LlmProvider> candidates = routing.candidates();
        LlmProviderException last = null;
        List<String> failures = new ArrayList<>();
        for (LlmProvider provider : candidates) {
            try {
                return provider.generate(effectiveRequest);
            } catch (LlmProviderException e) {
                // ADR-023: failures arrive already classified at the provider/adapter
                // boundary — the chain never parses exception strings, it only
                // aggregates the structured classes for the report and the throw.
                failures.add(provider.name() + ": " + e.getMessage()
                        + " (classified " + e.failureClass() + ")");
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
                last,
                last == null ? LlmFailureClass.UNKNOWN : last.failureClass());
    }

    /**
     * Streamed generation through the chain (tutor SSE tranche). Failover
     * semantics match {@link #generate} EXACTLY up to the first token: an
     * error arriving before any delta moves the stream to the next candidate
     * (the client has received nothing of this provider's output, so a
     * restart is invisible). Once the first delta is emitted the stream is
     * COMMITTED to that provider — a mid-stream failure propagates as a Flux
     * error (the caller surfaces an honest error event) because resuming on a
     * second provider would duplicate or interleave text.
     *
     * <p>Experiment pinning applies identically: a pinned experiment streams
     * from its pinned provider alone, never failing over.</p>
     */
    @Override
    public Flux<LlmDelta> stream(LlmRequest request) {
        Routing routing = routingOf(request);
        if (routing.candidates().isEmpty()) {
            return Flux.error(new LlmProviderException("chain", emptyChainMessage(request), null));
        }
        return streamWithFailover(routing.candidates(), routing.request(), new ArrayList<>());
    }

    private Flux<LlmDelta> streamWithFailover(List<LlmProvider> candidates, LlmRequest request,
                                              List<String> failures) {
        if (candidates.isEmpty()) {
            // same aggregate contract as the exhausted generate() path
            String detail = String.join(" | ", failures);
            log.warn("LLM stream exhausted before first token ({} of {} candidates failed): {}",
                    failures.size(), failures.size(), detail);
            return Flux.error(new LlmProviderException("chain",
                    "all providers failed before first token [" + detail + "]",
                    null, LlmFailureClass.UNKNOWN));
        }
        LlmProvider head = candidates.get(0);
        List<LlmProvider> rest = candidates.subList(1, candidates.size());
        return head.stream(request)
                .transform(flux -> flux.switchOnFirst((first, inner) -> {
                    if (first.isOnError()) {
                        Throwable t = first.getThrowable();
                        LlmProviderException e = t instanceof LlmProviderException pe ? pe
                                : new LlmProviderException(head.name(), String.valueOf(t), t,
                                        LlmFailureClass.UNKNOWN);
                        failures.add(head.name() + ": " + e.getMessage()
                                + " (classified " + e.failureClass() + ")");
                        return streamWithFailover(rest, request, failures);
                    }
                    // first delta (or an empty-complete) — committed to this provider
                    return inner;
                }));
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

    /** Resolved routing for one request: the effective (pin-model-applied)
     *  request and the candidate providers in failover order. Shared by the
     *  blocking and streaming paths so the two can never drift. */
    private record Routing(LlmRequest request, List<LlmProvider> candidates) {
    }

    private Routing routingOf(LlmRequest request) {
        if (request.experimentId() != null && !request.experimentId().isBlank()) {
            ExperimentPin pin = resolvePin(request.experimentId());
            LlmProvider pinned = requirePinned(request.experimentId(), pin);
            if (request.hasMedia() && !pinned.supportsMedia()) {
                // pinning cannot be used to smuggle an image to a text-only member
                throw new LlmProviderException("chain",
                        "experiment '" + request.experimentId() + "' carries media but is pinned to "
                                + "provider '" + pinned.name() + "' which does not support media — "
                                + "re-pin the experiment to a vision-capable provider",
                        null);
            }
            // §26.1 research pinning: experiment pin > caller model > provider default.
            // A pin that names a model always wins over a caller-supplied model — otherwise
            // any caller could silently drift a registered experiment off its model.
            LlmRequest effective = (pin.model() != null && !pin.model().isBlank())
                    ? request.withModel(pin.model())
                    : request;
            return new Routing(effective, List.of(pinned));
        }
        List<LlmProvider> candidates = orderedAvailable();
        if (request.hasMedia()) {
            candidates = candidates.stream().filter(LlmProvider::supportsMedia).toList();
        }
        return new Routing(request, candidates);
    }

    /** Distinct exhaustion message for media requests — an operator reading the
     *  503 must see THAT the problem is missing vision capability, not just an
     *  empty chain. */
    private static String emptyChainMessage(LlmRequest request) {
        return request.hasMedia()
                ? "no vision-capable LLM provider available in chain"
                : "no available LLM provider in chain";
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
