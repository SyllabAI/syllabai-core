package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Failover chain tests with fake providers — the §26.1 behaviour contract:
 * Groq → Gemini → OpenRouter, skip unavailable, failover on error, cooldown.
 */
class FailoverLlmChainTest {

    private static final LlmRequest REQUEST = LlmRequest.of("system", "user");

    /** Scripted fake provider. */
    private static final class FakeProvider implements LlmProvider {
        private final String name;
        private final boolean configured;
        private final RuntimeException failure;   // null = succeed
        private final LlmProviderHealth health;

        private FakeProvider(String name, boolean configured, RuntimeException failure) {
            this.name = name;
            this.configured = configured;
            this.failure = failure;
            this.health = new LlmProviderHealth(configured);
        }

        @Override public String name() { return name; }

        @Override public boolean available() { return configured && !health.inCooldown(); }

        @Override public LlmResponse generate(LlmRequest request) {
            if (failure != null) {
                health.recordFailure(failure.getMessage());
                throw new LlmProviderException(name, "generation failed", failure);
            }
            health.recordSuccess();
            return new LlmResponse("answer from " + name, name, "fake-model", 5, 10, 10);
        }

        @Override public LlmProviderHealth health() { return health; }
    }

    @Test
    @DisplayName("primary provider answers when healthy")
    void primaryWins() {
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", true, null),
                new FakeProvider("gemini", true, null)));
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("groq");
    }

    @Test
    @DisplayName("primary failure fails over to the next provider")
    void failoverOnPrimaryError() {
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", true, new IllegalStateException("429 rate limited")),
                new FakeProvider("gemini", true, null),
                new FakeProvider("openrouter", true, null)));
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("gemini");
    }

    @Test
    @DisplayName("unconfigured providers are skipped silently")
    void skipsUnconfigured() {
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", false, null),
                new FakeProvider("gemini", false, null),
                new FakeProvider("openrouter", true, null)));
        LlmResponse response = chain.generate(REQUEST);
        assertThat(response.providerName()).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("all providers failing raises a chain exception")
    void allFailing() {
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", true, new IllegalStateException("boom")),
                new FakeProvider("gemini", true, new IllegalStateException("boom"))));
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("all providers failed");
    }

    @Test
    @DisplayName("no configured provider → chain unavailable and clear error")
    void emptyChain() {
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", false, null),
                new FakeProvider("gemini", false, null)));
        assertThat(chain.available()).isFalse();
        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider");
    }

    @Test
    @DisplayName("consecutive failures trip the cooldown and exclude the provider")
    void cooldownAfterRepeatedFailures() {
        FakeProvider groq = new FakeProvider("groq", true, new IllegalStateException("boom"));
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                groq, new FakeProvider("gemini", true, null)));
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
        FailoverLlmChain chain = new FailoverLlmChain(java.util.List.of(
                new FakeProvider("groq", true, null),
                new FakeProvider("gemini", false, null)));
        assertThat(chain.memberHealth()).containsKeys("groq", "gemini");
        assertThat(chain.memberHealth().get("gemini").configured()).isFalse();
    }
}
