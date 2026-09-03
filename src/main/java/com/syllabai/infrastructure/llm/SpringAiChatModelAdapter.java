package com.syllabai.infrastructure.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Adapts a Spring AI {@link ChatModel} to the {@link LlmProvider} port (Master Spec
 * §23 Adapter pattern, §26 abstraction). Works for the OpenAI-compatible models
 * (Groq, OpenRouter) and the Google GenAI model (Gemini) alike — provider specifics
 * stay inside the bean construction in {@code LlmChainConfig}.
 */
public class SpringAiChatModelAdapter implements LlmProvider {

    private final String providerName;
    private final ChatModel chatModel;
    private final boolean configured;
    private final LlmProviderHealth health;

    public SpringAiChatModelAdapter(String providerName, ChatModel chatModel, boolean configured) {
        this.providerName = providerName;
        this.chatModel = chatModel;
        this.configured = configured;
        this.health = new LlmProviderHealth(configured);
    }

    @Override
    public String name() {
        return providerName;
    }

    @Override
    public boolean available() {
        return configured && !health.inCooldown();
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        if (!configured || chatModel == null) {
            throw new LlmProviderException(providerName, "provider not configured", null);
        }
        long started = System.nanoTime();
        try {
            ChatOptions runtimeOptions = options(request);
            Prompt prompt = runtimeOptions == null
                    ? new Prompt(messages(request))
                    : new Prompt(messages(request), runtimeOptions);
            ChatResponse response = chatModel.call(prompt);
            String text = extractText(response);
            Usage usage = response.getMetadata().getUsage();
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            health.recordSuccess();
            // report the model actually used: an explicit request/pin model overrides
            // the provider default (§19 reproducibility — telemetry must not claim the
            // default model when an experiment pin routed to a specific one)
            String effectiveModel = (request.model() != null && !request.model().isBlank())
                    ? request.model()
                    : (chatModel.getDefaultOptions() == null ? null
                            : chatModel.getDefaultOptions().getModel());
            return new LlmResponse(
                    text,
                    providerName,
                    effectiveModel,
                    latencyMs,
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens());
        } catch (LlmProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            health.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new LlmProviderException(providerName, "generation failed", e);
        }
    }

    @Override
    public LlmProviderHealth health() {
        return health;
    }

    private java.util.List<org.springframework.ai.chat.messages.Message> messages(LlmRequest request) {
        java.util.List<org.springframework.ai.chat.messages.Message> messages = new java.util.ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userPrompt()));
        return messages;
    }

    private ChatOptions options(LlmRequest request) {
        if (request.temperature() == null && request.maxTokens() == null
                && (request.model() == null || request.model().isBlank())) {
            return null;    // fall back to model defaults
        }
        ChatOptions.Builder<?> builder = ChatOptions.builder();
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        if (request.model() != null && !request.model().isBlank()) {
            builder.model(request.model());   // per-experiment model pin (§26.1)
        }
        return builder.build();
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        AssistantMessage message = response.getResult().getOutput();
        return message.getText() == null ? "" : message.getText();
    }
}
