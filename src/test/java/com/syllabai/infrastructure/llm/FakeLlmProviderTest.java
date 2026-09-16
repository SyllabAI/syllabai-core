package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Self-tests for the reusable deterministic fixture (ADR-023 Slice B): the fake
 * must return its configured response unchanged, emit the exact scripted failure
 * (class + adapter message contract), record invocation metadata (never prompt
 * text), and expose cross-provider invocation order.
 */
class FakeLlmProviderTest {

    private static final LlmRequest REQUEST = LlmRequest.of("system", "user");

    @Test
    @DisplayName("the configured response is returned unchanged")
    void deterministicResponse() {
        LlmResponse scripted = new LlmResponse("deterministic answer", "groq", "fake-model", 7, 11, 13);
        FakeLlmProvider provider = FakeLlmProvider.named("groq").respondsWith(scripted);

        LlmResponse first = provider.generate(REQUEST);
        LlmResponse second = provider.generate(REQUEST);

        assertThat(first).isSameAs(scripted);
        assertThat(second).isSameAs(scripted);
    }

    @Test
    @DisplayName("a scripted failure class is emitted with the adapter message contract")
    void classifiedFailure() {
        FakeLlmProvider provider = FakeLlmProvider.named("gemini")
                .alwaysFails(LlmFailureClass.RATE_LIMITED, "429 quota");

        assertThatThrownBy(() -> provider.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("generation failed")
                .hasMessageContaining("429 quota")
                .extracting(e -> ((LlmProviderException) e).failureClass())
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
        // the failure reached the REAL health tracker — cooldown semantics apply
        assertThat(provider.health().snapshot().lastFailureClass())
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
        assertThat(provider.health().snapshot().requestsToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("a raw-cause failure mirrors the adapter message contract")
    void rawCauseFailure() {
        FakeLlmProvider provider = FakeLlmProvider.named("groq")
                .alwaysFails(new IllegalStateException("403 Forbidden"));

        assertThatThrownBy(() -> provider.generate(REQUEST))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("IllegalStateException: 403 Forbidden");
    }

    @Test
    @DisplayName("failsNext queues N classified failures, then the default script takes over")
    void scriptedThenDefault() {
        FakeLlmProvider provider = FakeLlmProvider.named("groq").failsNext(2, LlmFailureClass.TIMEOUT);

        assertThatThrownBy(() -> provider.generate(REQUEST))
                .extracting(e -> ((LlmProviderException) e).failureClass())
                .isEqualTo(LlmFailureClass.TIMEOUT);
        assertThatThrownBy(() -> provider.generate(REQUEST))
                .extracting(e -> ((LlmProviderException) e).failureClass())
                .isEqualTo(LlmFailureClass.TIMEOUT);
        assertThat(provider.generate(REQUEST).text()).isEqualTo("answer from groq");
        assertThat(provider.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("call records carry metadata only — never prompt text")
    void recordsCarryNoPromptText() {
        FakeLlmProvider provider = FakeLlmProvider.named("groq");
        provider.generate(new LlmRequest("SECRET-SYSTEM-PROMPT", "SECRET-USER-QUESTION",
                0.3, 128, "some-model", "exp-9"));

        assertThat(provider.callCount()).isEqualTo(1);
        FakeLlmProvider.Call call = provider.lastCall();
        assertThat(call.providerName()).isEqualTo("groq");
        assertThat(call.model()).isEqualTo("some-model");
        assertThat(call.experimentId()).isEqualTo("exp-9");
        assertThat(call.temperature()).isEqualTo(0.3);
        assertThat(call.maxTokens()).isEqualTo(128);
        assertThat(call.at()).isNotNull();
        // the Call record must not carry prompt content
        assertThat(call.toString()).doesNotContain("SECRET");
        assertThat(provider.requestedModels()).containsExactly("some-model");
    }

    @Test
    @DisplayName("invocation order lands in the shared list")
    void sharedOrder() {
        List<String> order = new ArrayList<>();
        FakeLlmProvider a = FakeLlmProvider.named("a").recordOrderInto(order);
        FakeLlmProvider b = FakeLlmProvider.named("b").recordOrderInto(order);

        a.generate(REQUEST);
        b.generate(REQUEST);
        a.generate(REQUEST);

        assertThat(order).containsExactly("a", "b", "a");
    }

    @Test
    @DisplayName("unconfigured fakes report unavailable and are never called by the chain contract")
    void unconfiguredFake() {
        FakeLlmProvider provider = FakeLlmProvider.unconfigured("groq");
        assertThat(provider.available()).isFalse();
        assertThat(provider.name()).isEqualTo("groq");
    }
}
