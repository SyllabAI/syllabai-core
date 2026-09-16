package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 Slice D — {@code syllabai.llm.mode} fail-closed contract:
 *
 * <ul>
 *   <li>TEST mode must never construct real provider adapters, even when API keys
 *       are present in the environment — generation fails loudly instead of
 *       silently spending Groq/Gemini/OpenRouter quota.</li>
 *   <li>PRODUCTION (default) keeps the existing wiring (T-036 regression).</li>
 *   <li>LIVE registers real providers like production — actual live calls are
 *       additionally gated by LIVE_LLM_TESTS=explicit in the test layer.</li>
 * </ul>
 */
class LlmChainModeTest {

    private static LlmChainProperties props(LlmMode mode) {
        return new LlmChainProperties(
                new LlmChainProperties.Groq(true, "dummy-groq-key", null, null),
                new LlmChainProperties.Gemini(true, "dummy-gemini-key", null),
                new LlmChainProperties.OpenRouter(true, "dummy-openrouter-key", null, null),
                new LlmChainProperties.Chain(30, 60, 3, 1000),
                Map.of(),
                mode);
    }

    private static FailoverLlmChain build(LlmChainProperties properties) {
        return new LlmChainConfig(properties).failoverLlmChain(List.of());
    }

    @Test
    @DisplayName("TEST mode + real keys present → providers unconfigured, generation fails closed")
    void testModeFailsClosedWithKeysPresent() {
        FailoverLlmChain chain = build(props(LlmMode.TEST));

        // every member registered but NONE configured — no ChatModel exists, so no
        // code path can reach Groq/Gemini/OpenRouter
        assertThat(chain.member("groq")).isPresent();
        assertThat(chain.member("groq").orElseThrow().available()).isFalse();
        assertThat(chain.member("gemini").orElseThrow().available()).isFalse();
        assertThat(chain.member("openrouter").orElseThrow().available()).isFalse();
        assertThat(chain.available()).isFalse();

        // fail closed: loud failure, zero provider invocations
        assertThatThrownBy(() -> chain.generate(LlmRequest.of("system", "user")))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("no available LLM provider");
        assertThat(chain.member("groq").orElseThrow().health().snapshot().requestsToday())
                .isZero();
    }

    @Test
    @DisplayName("TEST mode + zero keys → clean boot, chain unavailable (same as CI path)")
    void testModeWithZeroKeysBootsClean() {
        LlmChainProperties zeroKeys = new LlmChainProperties(
                new LlmChainProperties.Groq(true, null, null, null),
                new LlmChainProperties.Gemini(true, null, null),
                new LlmChainProperties.OpenRouter(true, null, null, null),
                new LlmChainProperties.Chain(30, 60, 3, 1000),
                Map.of(),
                LlmMode.TEST);
        FailoverLlmChain chain = build(zeroKeys);
        assertThat(chain.available()).isFalse();
    }

    @Test
    @DisplayName("PRODUCTION mode + keys → providers configured (existing T-036 behaviour)")
    void productionModeRegistersProviders() {
        FailoverLlmChain chain = build(props(LlmMode.PRODUCTION));
        assertThat(chain.member("groq").orElseThrow().available()).isTrue();
        assertThat(chain.member("gemini").orElseThrow().available()).isTrue();
        assertThat(chain.member("openrouter").orElseThrow().available()).isTrue();
    }

    @Test
    @DisplayName("LIVE mode + keys → providers configured (live calls need LIVE_LLM_TESTS=explicit in tests)")
    void liveModeRegistersProviders() {
        FailoverLlmChain chain = build(props(LlmMode.LIVE));
        assertThat(chain.member("groq").orElseThrow().available()).isTrue();
        assertThat(chain.available()).isTrue();
    }

    @Test
    @DisplayName("missing/blank mode defaults to PRODUCTION (config drift cannot silently disable LLM)")
    void missingModeDefaultsToProduction() {
        LlmChainProperties withoutMode = new LlmChainProperties(
                new LlmChainProperties.Groq(true, "dummy-groq-key", null, null),
                new LlmChainProperties.Gemini(false, null, null),
                new LlmChainProperties.OpenRouter(false, null, null, null),
                new LlmChainProperties.Chain(30, 60, 3, 1000),
                Map.of(),
                null);
        assertThat(withoutMode.mode()).isEqualTo(LlmMode.PRODUCTION);
        FailoverLlmChain chain = build(withoutMode);
        assertThat(chain.member("groq").orElseThrow().available()).isTrue();
    }

    @Test
    @DisplayName("TEST mode keeps the enabled/configured drift signal visible")
    void testModeShowsDriftSignal() {
        FailoverLlmChain chain = build(props(LlmMode.TEST));
        var snapshot = chain.member("groq").orElseThrow().health().snapshot();
        assertThat(snapshot.enabled()).isTrue();      // enabled by configuration…
        assertThat(snapshot.configured()).isFalse();  // …but suppressed by TEST mode
        assertThat(snapshot.effectiveModel()).isNotBlank(); // model it WOULD use
    }
}
