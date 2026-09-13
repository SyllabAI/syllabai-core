package com.syllabai.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding configuration (T-013, §26-style). Prefix {@code syllabai.embedding}.
 *
 * <p>The API key comes from the environment ({@code SYLLABAI_EMBEDDING_GEMINI_API_KEY});
 * its presence is the provider switch — no bean is built without it, mirroring the
 * chat chain's zero-key posture. {@code timeout-seconds} bounds each provider call
 * (C-5, same posture as {@code syllabai.llm.chain.timeout-seconds}); a hung Gemini
 * embedding request fails loud instead of pinning the caller indefinitely.</p>
 */
@ConfigurationProperties(prefix = "syllabai.embedding")
public record EmbeddingProperties(GeminiEmbedding gemini) {

    public EmbeddingProperties {
        if (gemini == null) gemini = new GeminiEmbedding(null, null, 768, null);
    }

    public record GeminiEmbedding(String apiKey, String model, int dimension,
                                  Integer timeoutSeconds) {
        public GeminiEmbedding {
            if (model == null || model.isBlank()) model = "text-embedding-004";
            if (dimension <= 0) dimension = 768;
            if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 30;
        }
    }
}
