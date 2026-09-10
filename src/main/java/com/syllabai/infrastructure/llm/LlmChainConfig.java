package com.syllabai.infrastructure.llm;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the §26.1 free-tier provider chain from {@code syllabai.llm.*} properties.
 *
 * <p>Providers register only when enabled <em>and</em> their API key is present, so
 * the application boots cleanly with zero keys (every non-LLM feature works) and the
 * chain report shows what is missing. All provider construction stays here — domain
 * services only ever see {@link LlmProvider} (§26).</p>
 */
@Configuration
public class LlmChainConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmChainConfig.class);

    private final LlmChainProperties properties;

    public LlmChainConfig(LlmChainProperties properties) {
        this.properties = properties;
    }

    @Bean
    public FailoverLlmChain failoverLlmChain(java.util.List<ExperimentPinResolver> registryResolvers) {
        List<LlmProvider> providers = new ArrayList<>();

        if (properties.groq().enabled() && hasKey(properties.groq().apiKey())) {
            providers.add(new SpringAiChatModelAdapter("groq", groqChatModel(), true));
            log.info("LLM provider registered: groq (model {})", properties.groq().model());
        } else {
            providers.add(new SpringAiChatModelAdapter("groq", null, false));
        }

        if (properties.gemini().enabled() && hasKey(properties.gemini().apiKey())) {
            providers.add(new SpringAiChatModelAdapter("gemini", geminiChatModel(), true));
            log.info("LLM provider registered: gemini (model {})", properties.gemini().model());
        } else {
            providers.add(new SpringAiChatModelAdapter("gemini", null, false));
        }

        if (properties.openRouter().enabled() && hasKey(properties.openRouter().apiKey())) {
            providers.add(new SpringAiChatModelAdapter("openrouter", openRouterChatModel(), true));
            log.info("LLM provider registered: openrouter (model {})", properties.openRouter().model());
        } else {
            providers.add(new SpringAiChatModelAdapter("openrouter", null, false));
        }

        // Pin resolution order (§26.1): deployment configuration first, then the
        // experiments research registry (JpaExperimentPinResolver bean, if present).
        List<ExperimentPinResolver> resolvers = new ArrayList<>();
        resolvers.add(new PropertiesExperimentPinResolver(properties.experimentPins()));
        if (registryResolvers != null) {
            resolvers.addAll(registryResolvers);
        }
        return new FailoverLlmChain(providers, new CompositeExperimentPinResolver(resolvers));
    }

    // ── ChatModel construction (provider specifics stay below this line) ──

    private ChatModel groqChatModel() {
        // OpenAiChatModel.Builder constructs BOTH sync and async clients via
        // OpenAiSetup (SpringAiOpenAiHttpClient) from baseUrl/apiKey carried in
        // OpenAiChatOptions. Do NOT build a raw ClientOptions client and pass it
        // via .openAiClient(...) alone: the builder then constructs the ASYNC
        // client from empty options and fails with 'At least one credential
        // source must be specified'. (T-036: this path only executes when a
        // Groq key is PRESENT, so CI's zero-key boots never covered it.)
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(properties.groq().model())
                        .baseUrl(properties.groq().baseUrl())
                        .apiKey(properties.groq().apiKey())
                        .temperature(0.2)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private ChatModel geminiChatModel() {
        Client genAiClient = Client.builder()
                .apiKey(properties.gemini().apiKey())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .options(GoogleGenAiChatOptions.builder()
                        .model(toGeminiModel(properties.gemini().model()))
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private ChatModel openRouterChatModel() {
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(properties.openRouter().model())
                        .baseUrl(properties.openRouter().baseUrl())
                        .apiKey(properties.openRouter().apiKey())
                        .temperature(0.2)
                        .build())
                .toolCallingManager(ToolCallingManager.builder().build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private GoogleGenAiChatModel.ChatModel toGeminiModel(String configured) {
        // "gemini-2.5-flash" → enum GEMINI_2_5_FLASH
        String enumName = configured.replace('-', '_').replace('.', '_').toUpperCase();
        return GoogleGenAiChatModel.ChatModel.valueOf(enumName);
    }

    private static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
