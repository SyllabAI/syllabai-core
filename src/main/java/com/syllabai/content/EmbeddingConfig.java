package com.syllabai.content;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Builds the {@link EmbeddingProvider} from {@code syllabai.embedding.*} properties
 * (T-013). The bean exists only when a Gemini API key is set (the key's presence is
 * the switch — there is exactly one embedding provider by design, no chain, no
 * failover). Otherwise the application boots with zero keys; ingestion and chunking
 * work fully and embedding/search fail loudly on explicit use, exactly like the chat
 * chain's unconfigured providers.
 */
@Configuration
public class EmbeddingConfig {

    /**
     * True when {@code syllabai.embedding.gemini.api-key} binds to a non-blank
     * value. A dedicated condition (rather than {@code @ConditionalOnProperty}) is
     * required because application.yml bridges the documented
     * {@code SYLLABAI_EMBEDDING_GEMINI_API_KEY} environment variable with an empty
     * default (the same bridging every other dashed property uses) — a property
     * that is present-but-blank must still count as "no key" so the app boots
     * provider-less exactly as it did before the bridge existed.
     */
    static class GeminiApiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment()
                    .getProperty("syllabai.embedding.gemini.api-key");
            return key != null && !key.isBlank();
        }
    }

    @Bean
    @Conditional(GeminiApiKeyPresent.class)
    public EmbeddingProvider geminiEmbeddingProvider(EmbeddingProperties properties) {
        var gemini = properties.gemini();
        if (gemini.dimension() != 768) {
            // fail fast: the V11 column is vector(768); a mismatch would only surface
            // after API calls have already been spent
            throw new IllegalStateException("syllabai.embedding.gemini.dimension must be 768 "
                    + "(pgvector column vector(768), migration V11) — was " + gemini.dimension());
        }
        return new GeminiEmbeddingProvider(gemini.apiKey(), gemini.model(), gemini.dimension(),
                gemini.timeoutSeconds());
    }
}
