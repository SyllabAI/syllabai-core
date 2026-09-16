package com.syllabai.infrastructure.llm;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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

        // wire the documented chain policy into provider health — these were
        // configured properties that the hard-coded health class ignored
        LlmChainProperties.Chain chainCfg = properties.chain();
        int threshold = chainCfg == null ? 3 : Math.max(1, chainCfg.failureThreshold());
        int cooldown = chainCfg == null ? 60 : Math.max(1, chainCfg.cooldownSeconds());
        int timeout = chainCfg == null ? 30 : Math.max(1, chainCfg.timeoutSeconds());
        // ADR-023: configured LOCAL daily budget per provider (routing guard — not a
        // provider-quota claim). Enforced through LlmProviderHealth.budgetExhausted().
        int dailyBudget = chainCfg == null ? 1000 : Math.max(1, chainCfg.dailyBudgetPerProvider());
        LlmMode mode = properties.mode() == null ? LlmMode.PRODUCTION : properties.mode();
        // ADR-023 fail-closed: in TEST mode real ChatModels are never constructed —
        // keys present in the environment are ignored, so no code path can silently
        // spend Groq/Gemini/OpenRouter quota. Tests wire deterministic fakes instead.
        boolean testMode = mode == LlmMode.TEST;

        registerProvider(providers, "groq", testMode, properties.groq().enabled(),
                properties.groq().apiKey(), properties.groq().model(),
                this::groqChatModel,
                request -> openAiRuntimeOptions(request, properties.groq().model()),
                threshold, cooldown, timeout, dailyBudget);
        registerProvider(providers, "gemini", testMode, properties.gemini().enabled(),
                properties.gemini().apiKey(), properties.gemini().model(),
                this::geminiChatModel,
                request -> genAiRuntimeOptions(request, properties.gemini().model()),
                threshold, cooldown, timeout, dailyBudget);
        registerProvider(providers, "openrouter", testMode, properties.openRouter().enabled(),
                properties.openRouter().apiKey(), properties.openRouter().model(),
                this::openRouterChatModel,
                request -> openAiRuntimeOptions(request, properties.openRouter().model()),
                threshold, cooldown, timeout, dailyBudget);

        // Pin resolution order (§26.1): deployment configuration first, then the
        // experiments research registry (JpaExperimentPinResolver bean, if present).
        List<ExperimentPinResolver> resolvers = new ArrayList<>();
        resolvers.add(new PropertiesExperimentPinResolver(properties.experimentPins()));
        if (registryResolvers != null) {
            resolvers.addAll(registryResolvers);
        }
        return new FailoverLlmChain(providers, new CompositeExperimentPinResolver(resolvers));
    }

    /**
     * Registers one chain member. Real adapters are constructed only when the mode
     * allows it AND the provider is enabled AND its key is present; otherwise the
     * member registers unconfigured (still visible in the chain report with the
     * model it WOULD use — the enabled/configured distinction is the drift signal).
     */
    private void registerProvider(List<LlmProvider> providers, String name, boolean testMode,
                                  boolean enabled, String apiKey, String defaultModel,
                                  java.util.function.Supplier<ChatModel> chatModelFactory,
                                  Function<LlmRequest, ChatOptions> runtimeOptionsFactory,
                                  int threshold, int cooldown, int timeout, int dailyBudget) {
        if (!testMode && enabled && hasKey(apiKey)) {
            providers.add(new SpringAiChatModelAdapter(name, chatModelFactory.get(), enabled, true,
                    threshold, cooldown, timeout, dailyBudget, defaultModel, runtimeOptionsFactory));
            log.info("LLM provider registered: {} (model {})", name, defaultModel);
        } else {
            if (testMode && enabled && hasKey(apiKey)) {
                log.warn("LLM mode=test: provider '{}' has an API key in the environment but it is "
                        + "IGNORED — real providers are never constructed in test mode "
                        + "(fail-closed, ADR-023)", name);
            }
            providers.add(new SpringAiChatModelAdapter(name, null, enabled, false,
                    threshold, cooldown, timeout, dailyBudget, defaultModel, runtimeOptionsFactory));
        }
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

    // ── runtime options factories ───────────────────────────────────────────
    // Spring AI 2.0.x asserts the PROMPT-level options are the CONCRETE provider
    // class: OpenAiChatModel.createRequest does Assert.isInstanceOf(OpenAiChatOptions)
    // ("Prompt options must be OpenAiChatOptions type") and GoogleGenAiChatModel
    // checkcasts to GoogleGenAiChatOptions directly. A generic ChatOptions built via
    // ChatOptions.builder() therefore threw ClassCastException on EVERY provider call
    // — the hidden root cause of the 2026-09-14 tutor outage. The adapter now
    // receives a per-provider factory that always produces the concrete type.

    /** OpenAI-compatible runtime options (Groq, OpenRouter); package-private for tests. */
    static ChatOptions openAiRuntimeOptions(LlmRequest request, String defaultModel) {
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model() : defaultModel;
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxTokens(request.maxTokens());
        }
        return builder.build();
    }

    /** Google GenAI runtime options; package-private for tests. */
    static ChatOptions genAiRuntimeOptions(LlmRequest request, String defaultModel) {
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model() : defaultModel;
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder().model(toGeminiModel(model));
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxOutputTokens(request.maxTokens());   // GenAI names the cap differently
        }
        return builder.build();
    }

    private static GoogleGenAiChatModel.ChatModel toGeminiModel(String configured) {
        // "gemini-2.5-flash" → enum GEMINI_2_5_FLASH
        String enumName = configured.replace('-', '_').replace('.', '_').toUpperCase();
        return GoogleGenAiChatModel.ChatModel.valueOf(enumName);
    }

    private static boolean hasKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }
}
