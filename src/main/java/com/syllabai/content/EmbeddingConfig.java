package com.syllabai.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    @ConditionalOnProperty(prefix = "syllabai.embedding.gemini", name = "api-key")
    public EmbeddingProvider geminiEmbeddingProvider(EmbeddingProperties properties) {
        var gemini = properties.gemini();
        if (gemini.dimension() != 768) {
            // fail fast: the V11 column is vector(768); a mismatch would only surface
            // after API calls have already been spent
            throw new IllegalStateException("syllabai.embedding.gemini.dimension must be 768 "
                    + "(pgvector column vector(768), migration V11) — was " + gemini.dimension());
        }
        return new GeminiEmbeddingProvider(gemini.apiKey(), gemini.model(), gemini.dimension());
    }
}
