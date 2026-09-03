package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Failover chain tests with fake providers — the §26.1 behaviour contract:
 * Groq → Gemini → OpenRouter, skip unavailable, failover on error, cooldown, and
 * per-experiment pinning (pinned requests never drift; unpinned fail loudly).
 */
class FailoverLlmChainTest {

    private static final LlmRequest REQUEST = LlmRequest.of("system", "user");
    private static final LlmRequest EXPERIMENT_REQUEST =
            new LlmRequest("system", "user", null, null, null, "exp-1");

    private static final ExperimentPinResolver UNPINNED = id -> Optional.empty();

    /** Scripted fake provider. */
    private static final class FakeProvider implements LlmProvider {
        private final String name;
        private final boolean configured;
        private final RuntimeException failure;   // null = succeed
        private final LlmProviderHealth health;
        private int calls;
        private LlmRequest lastRequest;

        private FakeProvider(String name, boolean configured, RuntimeException failure) {
            this.name = name;
            this.configured = configured;
            this.failure = failure;
            this.health = new LlmProviderHealth(configured);
        }

        @Override public String name() { return name; }

        @Override public boolean available() { return configured && !health.inCooldown(); }

        @Override public LlmResponse generate(LlmRequest request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                health.recordFailure(failure.getMessage());
                throw new LlmProviderException(name, "generation failed", failure);
            }
            health.recordSuccess();
            return new LlmResponse("answer from " + name, name, "fake-model", 5, 10, 10);
        }

        @Override public LlmProviderHealth health() { return health; }

        private int calls() { return calls; }
        private LlmRequest lastRequest() { return lastRequest; }
    }

    @Test
    @DisplayName("primary provider answers when healthy")
    void primaryWins() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", true, null),
                new FakeProvider("gemini", true, null)), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("groq");
    }

    @Test
    @DisplayName("primary failure fails over to the next provider")
    void failoverOnPrimaryError() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", true, new IllegalStateException("429 rate limited")),
                new FakeProvider("gemini", true, null),
                new FakeProvider("openrouter", true, null)), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("unconfigured providers are skipped silently")
    void skipsUnconfigured() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", false, null),
                new FakeProvider("gemini", false, null),
                new FakeProvider("openrouter", true, null)), UNPINNED);
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("all providers failing raises a chain exception")
    void allFailing() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", true, new IllegalStateException("boom")),
                new FakeProvider("gemini", true, new IllegalStateException("boom"))), UNPINNED);
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed");
    }

    @Test
    @DisplayName("no configured provider → chain unavailable and clear error")
    void emptyChain() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", false, null),
                new FakeProvider("gemini", false, null)), UNPINNED);
        assertThat(chain.available()).isFalse();
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider");
    }

    @Test
    @DisplayName("consecutive failures trip the cooldown and exclude the provider")
    void cooldownAfterRepeatedFailures() {
        FakeProvider groq = new FakeProvider("groq", true, new IllegalStateException("boom"));
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                groq, new FakeProvider("gemini", true, null)), UNPINNED);
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
                new FakeProvider("groq", true, null),
                new FakeProvider("gemini", false, null)), UNPINNED);
        assertThat(chain.memberHealth()).containsKeys("groq", "gemini");
        assertThat(chain.memberHealth().get("gemini").configured()).isFalse();
    }

    // ── experiment pinning (§26.1) ─────────────────────────────────────────────

    @Test
    @DisplayName("a pinned experiment is served exclusively by the pinned provider")
    void pinnedExperimentUsesPinnedProviderOnly() {
        FakeProvider groq = new FakeProvider("groq", true, null);
        FakeProvider gemini = new FakeProvider("gemini", true, null);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));
        LlmResponse response = chain.generate(EXPERIMENT_REQUEST);
        assertThat(response.providerName()).isEqualTo("groq");
        assertThat(gemini.calls()).isZero();
    }

    @Test
    @DisplayName("a pinned provider failure never fails over to another provider")
    void pinnedProviderFailureDoesNotFailOver() {
        FakeProvider groq = new FakeProvider("groq", true, new IllegalStateException("429"));
        FakeProvider gemini = new FakeProvider("gemini", true, null);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed");
        assertThat(gemini.calls()).isZero();
    }

    @Test
    @DisplayName("an unpinned experiment id fails loudly instead of silently drifting")
    void unpinnedExperimentFailsLoud() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", true, null)), UNPINNED);
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("not pinned")
                .hasMessageContaining("exp-1");
    }

    @Test
    @DisplayName("a pin naming an unknown provider fails with the registered providers")
    void pinToUnknownProviderFails() {
        FailoverLlmChain chain = new FailoverLlmChain(List.of(
                new FakeProvider("groq", true, null)),
                id -> Optional.of(new ExperimentPin(id, "azure", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("unknown provider")
                .hasMessageContaining("azure");
    }

    @Test
    @DisplayName("a pin to an unavailable provider fails instead of failing over")
    void pinToUnavailableProviderFails() {
        FakeProvider groq = new FakeProvider("groq", true, null);
        FakeProvider gemini = new FakeProvider("gemini", false, null);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "gemini", null)));
        assertThatThrownBy(() -> chain.generate(EXPERIMENT_REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("never fail over");
        assertThat(groq.calls()).isZero();
    }

    @Test
    @DisplayName("a pinned model overrides the provider default for the pinned request")
    void pinnedModelOverridesRequestModel() {
        FakeProvider groq = new FakeProvider("groq", true, null);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> Optional.of(new ExperimentPin(id, "groq", "llama-3.3-70b-versatile-pinned")));
        chain.generate(EXPERIMENT_REQUEST);
        assertThat(groq.lastRequest().model()).isEqualTo("llama-3.3-70b-versatile-pinned");
    }

    @Test
    @DisplayName("an explicit request model is not replaced by the pin's model")
    void explicitRequestModelWins() {
        FakeProvider groq = new FakeProvider("groq", true, null);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq),
                id -> Optional.of(new ExperimentPin(id, "groq", "pinned-model")));
        chain.generate(new LlmRequest("system", "user", null, null, "explicit-model", "exp-1"));
        assertThat(groq.lastRequest().model()).isEqualTo("explicit-model");
    }
}
