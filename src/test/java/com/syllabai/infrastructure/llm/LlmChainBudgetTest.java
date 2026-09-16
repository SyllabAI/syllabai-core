package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 daily-budget enforcement at the CHAIN level: a provider that consumed
 * its configured local budget is ineligible until UTC day rollover, and the chain
 * fails over to the next eligible member. This is a local routing guard — not a
 * claim about any provider's upstream quota.
 */
class LlmChainBudgetTest {

    private static final LlmRequest REQUEST = LlmRequest.of("system", "user");
    private static final ExperimentPinResolver UNPINNED = id -> Optional.empty();

    @Test
    @DisplayName("provider A budget exhausted → eligible B is selected")
    void exhaustedProviderFailsOverToEligibleOne() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 2);   // local budget 2
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini", 100);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini), UNPINNED);

        chain.generate(REQUEST);   // groq: 1/2
        chain.generate(REQUEST);   // groq: 2/2 — budget reached
        LlmResponse third = chain.generate(REQUEST);   // groq ineligible → gemini

        assertThat(third.providerName()).isEqualTo("gemini");
        assertThat(groq.callCount()).isEqualTo(2);
        assertThat(gemini.callCount()).isEqualTo(1);
        assertThat(groq.available()).isFalse();        // budget-exhausted ⇒ ineligible
        assertThat(groq.health().snapshot().remainingLocalBudget()).isZero();
    }

    @Test
    @DisplayName("every provider exhausted → chain fails loudly with no available provider")
    void allExhaustedFailsLoud() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 1);
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini", 1);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini), UNPINNED);

        chain.generate(REQUEST);   // groq 1/1
        chain.generate(REQUEST);   // gemini 1/1

        assertThatThrownBy(() -> chain.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider");
        assertThat(groq.callCount()).isEqualTo(1);
        assertThat(gemini.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("chain availability reflects budget exhaustion")
    void chainAvailabilityReflectsBudget() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 1);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq), UNPINNED);
        assertThat(chain.available()).isTrue();
        chain.generate(REQUEST);
        assertThat(chain.available()).isFalse();
    }

    @Test
    @DisplayName("a pinned experiment to a budget-exhausted provider fails — never silently fails over")
    void pinnedExperimentRespectsBudgetGuard() {
        FakeLlmProvider groq = FakeLlmProvider.named("groq", 1);
        FakeLlmProvider gemini = FakeLlmProvider.named("gemini", 100);
        FailoverLlmChain chain = new FailoverLlmChain(List.of(groq, gemini),
                id -> Optional.of(new ExperimentPin(id, "groq", null)));

        chain.generate(REQUEST);   // consumes groq's budget (1/1)

        LlmRequest pinned = new LlmRequest("system", "user", null, null, null, "exp-1");
        assertThatThrownBy(() -> chain.generate(pinned))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("never fail over");
        assertThat(gemini.callCount()).isZero();
    }
}
