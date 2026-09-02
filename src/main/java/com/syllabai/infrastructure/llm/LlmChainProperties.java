package com.syllabai.infrastructure.llm;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Free-tier provider-chain configuration (§26.1, ADR-009). Prefix {@code syllabai.llm}.
 *
 * <p>API keys come from environment variables; when absent the provider simply
 * registers as unconfigured and the chain skips it — the application boots and
 * serves everything that does not need LLM calls.</p>
 */
@ConfigurationProperties(prefix = "syllabai.llm")
public record LlmChainProperties(
        Groq groq,
        Gemini gemini,
        OpenRouter openRouter,
        Chain chain,
        Map<String, String> experimentPins) {

    public record Groq(boolean enabled, String apiKey, String baseUrl, String model) {
        public Groq {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.groq.com/openai";
            if (model == null || model.isBlank()) model = "llama-3.3-70b-versatile";
        }
    }

    public record Gemini(boolean enabled, String apiKey, String model) {
        public Gemini {
            if (model == null || model.isBlank()) model = "gemini-2.5-flash";
        }
    }

    public record OpenRouter(boolean enabled, String apiKey, String baseUrl, String model) {
        public OpenRouter {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://openrouter.ai/api/v1";
            if (model == null || model.isBlank()) model = "meta-llama/llama-3.3-70b-instruct:free";
        }
    }

    public record Chain(int timeoutSeconds, int cooldownSeconds, int failureThreshold,
                        int dailyBudgetPerProvider) {
        public Chain {
            if (timeoutSeconds <= 0) timeoutSeconds = 30;
            if (cooldownSeconds <= 0) cooldownSeconds = 60;
            if (failureThreshold <= 0) failureThreshold = 3;
            if (dailyBudgetPerProvider <= 0) dailyBudgetPerProvider = 1000;
        }
    }

    public LlmChainProperties {
        if (groq == null) groq = new Groq(false, null, null, null);
        if (gemini == null) gemini = new Gemini(false, null, null);
        if (openRouter == null) openRouter = new OpenRouter(false, null, null, null);
        if (chain == null) chain = new Chain(30, 60, 3, 1000);
        if (experimentPins == null) experimentPins = Map.of();
    }

    /** Chain order per §26.1: Groq → Gemini → OpenRouter. */
    List<String> chainOrder() {
        return List.of("groq", "gemini", "openrouter");
    }
}
