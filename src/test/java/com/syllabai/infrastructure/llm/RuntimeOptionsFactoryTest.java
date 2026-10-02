package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
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

    @Test
    @DisplayName("reasoning-effort knob: reasoning_effort wire value on the OpenAI factory")
    void openAiFactoryCarriesReasoningEffort() {
        ChatOptions withEffort = LlmChainConfig.openAiRuntimeOptions(
                LlmRequest.of("sys", "user").withReasoningEffort(LlmReasoningEffort.LOW),
                "default-model");
        assertThat(((OpenAiChatOptions) withEffort).getReasoningEffort()).isEqualTo("low");

        ChatOptions minimal = LlmChainConfig.openAiRuntimeOptions(
                LlmRequest.of("sys", "user").withReasoningEffort(LlmReasoningEffort.MINIMAL),
                "default-model");
        assertThat(((OpenAiChatOptions) minimal).getReasoningEffort()).isEqualTo("minimal");

        // unset = provider default, exactly the pre-knob behavior
        ChatOptions unset = LlmChainConfig.openAiRuntimeOptions(
                LlmRequest.of("sys", "user"), "default-model");
        assertThat(((OpenAiChatOptions) unset).getReasoningEffort()).isNull();
    }

    @Test
    @DisplayName("reasoning-effort knob: thinkingLevel enum mapping on the GenAI factory")
    void genAiFactoryCarriesThinkingLevel() {
        ChatOptions low = LlmChainConfig.genAiRuntimeOptions(
                LlmRequest.of("sys", "user").withReasoningEffort(LlmReasoningEffort.LOW),
                "gemini-3.6-flash");
        assertThat(((GoogleGenAiChatOptions) low).getThinkingLevel())
                .isEqualTo(GoogleGenAiThinkingLevel.LOW);

        ChatOptions unset = LlmChainConfig.genAiRuntimeOptions(
                LlmRequest.of("sys", "user"), "gemini-3.6-flash");
        assertThat(((GoogleGenAiChatOptions) unset).getThinkingLevel()).isNull();
    }

    @Test
    @DisplayName("knob copy semantics: survives model/media copies, cleared by null")
    void reasoningEffortCopySemantics() {
        LlmRequest request = LlmRequest.of("sys", "user").withReasoningEffort(LlmReasoningEffort.LOW);
        assertThat(request.withModel("pinned").reasoningEffort()).isEqualTo(LlmReasoningEffort.LOW);
        assertThat(request.withMedia(null).reasoningEffort()).isEqualTo(LlmReasoningEffort.LOW);
        assertThat(request.withReasoningEffort(null).reasoningEffort()).isNull();
        // the legacy arities keep the pre-knob contract (provider default)
        assertThat(new LlmRequest("s", "u", null, null, null, null).reasoningEffort()).isNull();
        assertThat(new LlmRequest("s", "u", null, null, null, null, null).reasoningEffort()).isNull();
    }
}
