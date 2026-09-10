package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LlmChainConfig construction tests — the T-036 deployment regression.
 *
 * <p>The first real Render deploy failed to boot with
 * {@code IllegalStateException: `httpClient` is required, but was not set} from
 * {@code ClientOptions$Builder.build()}: groqChatModel()/openRouterChatModel()
 * built raw ClientOptions without an HTTP transport. CI never caught it because
 * the suite boots with zero LLM keys by design — the broken path only executes
 * when an API key is PRESENT. These tests exercise exactly that path with dummy
 * keys; client construction performs no network I/O, so they stay hermetic.</p>
 */
class LlmChainConfigConstructionTest {

    private static LlmChainProperties props(String groqKey, String openRouterKey, String geminiKey) {
        return new LlmChainProperties(
                new LlmChainProperties.Groq(groqKey != null, groqKey, null, null),
                new LlmChainProperties.Gemini(geminiKey != null, geminiKey, null),
                new LlmChainProperties.OpenRouter(openRouterKey != null, openRouterKey, null, null),
                new LlmChainProperties.Chain(30, 60, 3, 1000),
                java.util.Map.of());
    }

    private static FailoverLlmChain buildChain(LlmChainProperties properties) {
        LlmChainConfig config = new LlmChainConfig(properties);
        return config.failoverLlmChain(List.of());
    }

    @Test
    @DisplayName("groq key present: provider constructs and registers as available (T-036 regression)")
    void groqKeyPresentRegistersProvider() {
        FailoverLlmChain chain = buildChain(props("dummy-groq-key", null, null));

        assertThat(chain.member("groq")).isPresent();
        assertThat(chain.member("groq").orElseThrow().available()).isTrue();
    }

    @Test
    @DisplayName("openrouter key present: provider constructs and registers as available (T-036 regression)")
    void openRouterKeyPresentRegistersProvider() {
        FailoverLlmChain chain = buildChain(props(null, "dummy-openrouter-key", null));

        assertThat(chain.member("openrouter")).isPresent();
        assertThat(chain.member("openrouter").orElseThrow().available()).isTrue();
    }

    @Test
    @DisplayName("all keys present: chain boots with every provider configured")
    void allKeysPresentRegistersFullChain() {
        assertThatCode(() -> {
            FailoverLlmChain chain = buildChain(props("dummy-groq-key", "dummy-openrouter-key", "dummy-gemini-key"));
            assertThat(chain.member("groq").orElseThrow().available()).isTrue();
            assertThat(chain.member("openrouter").orElseThrow().available()).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("zero keys (CI path): chain still boots with every provider unconfigured")
    void zeroKeysChainBootsUnconfigured() {
        FailoverLlmChain chain = buildChain(props(null, null, null));

        assertThat(chain.member("groq")).isPresent();
        assertThat(chain.member("groq").orElseThrow().available()).isFalse();
        assertThat(chain.member("openrouter").orElseThrow().available()).isFalse();
        assertThat(chain.available()).isFalse();
    }
}
