package com.syllabai.content;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModelName;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;

/**
 * Gemini text embeddings on the free tier (ADR-009). Wraps two Spring AI
 * {@link GoogleGenAiTextEmbeddingModel} instances — RETRIEVAL_DOCUMENT for indexing
 * and RETRIEVAL_QUERY for queries — because task type is fixed per model instance.
 *
 * <p>Constructed only by {@link EmbeddingConfig} when a key is present; all Spring AI
 * embedding autoconfiguration stays excluded (application.yml), so this manual
 * construction mirrors {@code LlmChainConfig} for the chat chain.</p>
 */
class GeminiEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingProvider.class);

    private final EmbeddingModel documentModel;
    private final EmbeddingModel queryModel;
    private final String model;
    private final int dimension;

    GeminiEmbeddingProvider(String apiKey, String model, int dimension) {
        GoogleGenAiTextEmbeddingModelName modelName = toModelName(model);
        this.documentModel = new GoogleGenAiTextEmbeddingModel(
                GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build(),
                GoogleGenAiTextEmbeddingOptions.builder()
                        .model(modelName)
                        .taskType(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT)
                        .dimensions(dimension)
                        .build());
        this.queryModel = new GoogleGenAiTextEmbeddingModel(
                GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build(),
                GoogleGenAiTextEmbeddingOptions.builder()
                        .model(modelName)
                        .taskType(GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY)
                        .dimensions(dimension)
                        .build());
        this.model = modelName.name().toLowerCase().replace('_', '-');
        this.dimension = dimension;
        log.info("Embedding provider registered: gemini {} ({} dimensions)", this.model, dimension);
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public float[] embedDocument(String text) {
        return documentModel.embed(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return queryModel.embed(text);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return documentModel.embed(texts);
    }

    private static GoogleGenAiTextEmbeddingModelName toModelName(String configured) {
        // "text-embedding-004" → enum TEXT_EMBEDDING_004
        String enumName = configured.replace('-', '_').replace('.', '_').toUpperCase();
        return GoogleGenAiTextEmbeddingModelName.valueOf(enumName);
    }
}
