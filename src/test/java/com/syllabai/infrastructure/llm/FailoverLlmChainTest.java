package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Failover chain tests on the reusable {@link FakeLlmProvider} fixture (ADR-023
 * Slice B) — the §26.1 behaviour contract: Groq → Gemini → OpenRouter, skip
 * unavailable, failover on error, cooldown, structured failure classes, and
 * per-experiment pinning (pinned requests never drift; unpinned fail loudly).
 */
class FailoverLlmChainTest {

    private static final LlmRequest REQUEST = LlmRequest.of("system", "user");
    private static final LlmRequest EXPERIMENT_REQUEST =
            new LlmRequest("system", "user", null, null, null, "exp-1");

    private static final ExperimentPinResolver UNPINNED = id -> Optional.empty();

    @Test
    @DisplayName("primary provider answers when healthy")
    void primaryWins() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq"),
                FakeLlmProvider.named("gemini")), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("groq");
    }

    @Test
    @DisplayName("primary failure fails over to the next provider")
    void failoverOnPrimaryError() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq").alwaysFails(new IllegalStateException("429 rate limited")),
                FakeLlmProvider.named("gemini"),
                FakeLlmProvider.named("openrouter")), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("unconfigured providers are skipped silently")
    void skipsUnconfigured() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.unconfigured("groq"),
                FakeLlmProvider.unconfigured("gemini"),
                FakeLlmProvider.named("openrouter")), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("all providers failing raises a chain exception")
    void allFailing() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq").alwaysFails(new IllegalStateException("boom")),
                FakeLlmProvider.named("gemini").alwaysFails(new IllegalStateException("boom"))), UNPINNED);
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed");
    }

    @Test
    @DisplayName("chain exhaustion names every failed provider, its cause and its class (2026-09-14 outage lesson)")
    void exhaustedChainNamesEveryProviderCause() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq").alwaysFails(new IllegalStateException("403 Forbidden")),
                FakeLlmProvider.named("gemini").alwaysFails(new IllegalStateException("model retired")),
                FakeLlmProvider.named("openrouter").alwaysFails(LlmFailureClass.RATE_LIMITED)), UNPINNED);
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed")
                .hasMessageContaining("groq: ")
                .hasMessageContaining("403 Forbidden")
                .hasMessageContaining("gemini: ")
                .hasMessageContaining("model retired")
                .hasMessageContaining("openrouter: ")
                .hasMessageContaining("RATE_LIMITED")
                // ADR-023: the aggregate exception carries the last structured class
                .extracting(e -> ((LlmProviderException) e).failureClass())
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
    }

    @Test
    @DisplayName("no configured provider → chain unavailable and clear error")
    void emptyChain() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.unconfigured("groq"),
                FakeLlmProvider.unconfigured("gemini")), UNPINNED);
        assertThat(chain.available()).isFalse();
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider");
    }

    @Test
    @DisplayName("consecutive failures trip the cooldown and exclude the provider")
    void cooldownAfterRepeatedFailures() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq")
                .alwaysFails(new IllegalStateException("boom"));
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                groq, FakeLlmProvider.named("gemini")), UNPINNED);
        for (int i = 0; i < 3; i++) {
            chain.generate(REQUEST);   // groq fails 3×, gemini answers
        }
        assertThat(groq.health().snapshot().consecutiveFailures()).isEqualTo(3);
        assertThat(groq.available()).isFalse();   // in cooldown now
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("memberHealth reports every provider")
    void memberHealthReport() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq"),
                FakeLlmProvider.unconfigured("gemini")), UNPINNED);
        assertThat(chain.memberHealth()).containsKeys("groq", "gemini");
        assertThat(chain.memberHealth().get("gemini").configured()).isFalse();
    }

    @Test
    @DisplayName("the fixture records invocation count, model and cross-provider order")
    void invocationOrderIsRecorded() {
        List<String> order = new ArrayList<>();
        FakeLlmProvider groq = FakeLlmProvider.named("groq")
                .alwaysFails(LlmFailureClass.RATE_LIMITED).recordOrderInto(order);
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini").recordOrderInto(order);
        FakeLlmProvider openrouter = FakeLlmProvider.named("openrouter").recordOrderInto(order);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini, openrouter), UNPINNED);

        LlmResponse response = chain.generate(REQUEST);

        assertThat(response.providerName()).isEqualTo("gemini");
        assertThat(order).containsExactly("groq", "gemini");   // openrouter never tried
        assertThat(groq.callCount()).isEqualTo(1);
        assertThat(gemini.callCount()).isEqualTo(1);
        assertThat(openrouter.callCount()).isZero();
        assertThat(gemini.lastCall().providerName()).isEqualTo("gemini");
        assertThat(groq.lastCall().experimentId()).isNull();
    }

    // ── experiment pinning (§26.1) ─────────────────────────────────────────────

    @Test
    @DisplayName("a pinned experiment is served exclusively by the pinned provider")
    void pinnedExperimentUsesPinnedProviderOnly() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));
        LlmResponse response = chain.generate(EXPERIMENT_REQUEST);
        assertThat(response.providerName()).isEqualTo("groq");
        assertThat(gemini.callCount()).isZero();
    }

    @Test
    @DisplayName("a pinned provider failure never fails over to another provider")
    void pinnedProviderFailureDoesNotFailOver() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq")
                .alwaysFails(new IllegalStateException("429"));
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed");
        assertThat(gemini.callCount()).isZero();
    }

    @Test
    @DisplayName("an unpinned experiment id fails loudly instead of silently drifting")
    void unpinnedExperimentFailsLoud() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq")), UNPINNED);
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("not pinned")
                .hasMessageContaining("exp-1");
    }

    @Test
    @DisplayName("a pin naming an unknown provider fails with the registered providers")
    void pinToUnknownProviderFails() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                FakeLlmProvider.named("groq")),
                id -> Optional.of(new ExperimentPin(id, "azure", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("unknown provider")
                .hasMessageContaining("azure");
    }

    @Test
    @DisplayName("a pin to an unavailable provider fails instead of failing over")
    void pinToUnavailableProviderFails() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FakeLlmProvider gemini = FakeLlmProvider.unconfigured("gemini");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "gemini", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("never fail over");
        assertThat(groq.callCount()).isZero();
    }

    @Test
    @DisplayName("a pinned model overrides the provider default for the pinned request")
    void pinnedModelOverridesProviderDefault() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> Optional.of(new ExperimentPin(id, "groq", "llama-3.3-70b-versatile-pinned")));
        chain.generate(EXPERIMENT_REQUEST);
        assertThat(groq.lastRequest().model()).isEqualTo("llama-3.3-70b-versatile-pinned");
    }

    @Test
    @DisplayName("§26.1 precedence: a caller-supplied model can NEVER override the experiment pin's model")
    void pinnedModelBeatsCallerModel() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> Optional.of(new ExperimentPin(id, "groq", "pinned-model")));
        // caller tries to drift the experiment to a different model — must be ignored
        chain.generate(new LlmRequest("system", "user", null, null, "caller-model", "exp-1"));
        assertThat(groq.lastRequest().model())
                .as("experiment pin > caller model > provider default (§26.1)")
                .isEqualTo("pinned-model");
    }

    @Test
    @DisplayName("a pin without a model lets the caller model pass through (caller > provider default)")
    void callerModelAppliesWhenPinHasNoModel() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq");
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));
        chain.generate(new LlmRequest("system", "user", null, null, "caller-model", "exp-1"));
        assertThat(groq.lastRequest().model()).isEqualTo("caller-model");
    }
}
