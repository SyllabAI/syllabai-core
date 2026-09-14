package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Spring AI 2.0.x asserts prompt-level options are the CONCRETE provider class —
 * a generic ChatOptions threw ClassCastException on every provider call and hid
 * the 2026-09-14 tutor outage behind "generation failed". These factories must
 * always produce the right concrete type with the request's overrides applied
 * and the provider's configured default model as fallback.
 */
class RuntimeOptionsFactoryTest {

    @Test
    @DisplayName("OpenAI factory: overrides + default model fallback")
    void openAiFactoryAppliesOverridesAndDefaults() {
        ChatOptions withOverrides = LlmChainConfig.openAiRuntimeOptions(
                LlmRequest.withOptions("sys", "user", 0.4, 256), "default-model");
        assertThat(withOverrides).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions oai = (OpenAiChatOptions) withOverrides;
        assertThat(oai.getModel()).isEqualTo("default-model");   // no pin → provider default
        assertThat(oai.getTemperature()).isEqualTo(0.4);
        assertThat(oai.getMaxTokens()).isEqualTo(256);

        ChatOptions pinned = LlmChainConfig.openAiRuntimeOptions(
                LlmRequest.withOptions("sys", "user", 0.4, 256).withModel("pinned-model"), "default-model");
        assertThat(((OpenAiChatOptions) pinned).getModel()).isEqualTo("pinned-model");

        ChatOptions bare = LlmChainConfig.openAiRuntimeOptions(LlmRequest.of("sys", "user"), "default-model");
        OpenAiChatOptions bareOptions = (OpenAiChatOptions) bare;
        assertThat(bareOptions.getModel()).isEqualTo("default-model");
        assertThat(bareOptions.getTemperature()).isNull();
        assertThat(bareOptions.getMaxTokens()).isNull();
    }

    @Test
    @DisplayName("GenAI factory: concrete type, maxOutputTokens naming, enum mapping")
    void genAiFactoryProducesConcreteOptions() {
        ChatOptions options = LlmChainConfig.genAiRuntimeOptions(
                LlmRequest.withOptions("sys", "user", 0.2, 900), "gemini-3.6-flash");
        assertThat(options).isInstanceOf(GoogleGenAiChatOptions.class);
        GoogleGenAiChatOptions gen = (GoogleGenAiChatOptions) options;
        assertThat(gen.getModel()).isEqualTo("gemini-3.6-flash");
        assertThat(gen.getTemperature()).isEqualTo(0.2);
        assertThat(gen.getMaxOutputTokens()).isEqualTo(900);   // NOT silently dropped
    }

    @Test
    @DisplayName("GenAI factory fails loudly on an unmappable model string")
    void genAiFactoryRejectsUnknownModel() {
        assertThatThrownBy(() -> LlmChainConfig.genAiRuntimeOptions(
                LlmRequest.of("sys", "user").withModel("not-a-gemini-model"), "gemini-3.6-flash"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
