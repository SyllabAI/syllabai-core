package com.syllabai.content;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /**
     * Daemon, cached worker pool so a hung Gemini embedding HTTP call cannot pin
     * the calling thread (and, on the indexing path, a pooled DB connection)
     * forever: the caller gives up after {@code timeoutSeconds}. This is the same
     * posture {@code SpringAiChatModelAdapter} takes for the chat providers
     * (§26.1 timeout) — the GenAI client exposes no per-call timeout of its own.
     * Stranded workers are daemons and die with the JVM.
     */
    private static final ExecutorService CALL_POOL = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "embedding-provider-call");
                t.setDaemon(true);
                return t;
            });

    private final EmbeddingModel documentModel;
    private final EmbeddingModel queryModel;
    private final String model;
    private final int dimension;
    private final int timeoutSeconds;

    GeminiEmbeddingProvider(String apiKey, String model, int dimension, int timeoutSeconds) {
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
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        log.info("Embedding provider registered: gemini {} ({} dimensions, {}s call timeout)",
                this.model, dimension, this.timeoutSeconds);
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
        return callWithTimeout(() -> documentModel.embed(text));
    }

    @Override
    public float[] embedQuery(String text) {
        return callWithTimeout(() -> queryModel.embed(text));
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return callWithTimeout(() -> documentModel.embed(texts));
    }

    /**
     * Runs the blocking provider call on the shared daemon pool and gives up after
     * {@code syllabai.embedding.gemini.timeout-seconds} (default 30s), so a hung
     * Gemini call degrades into a loud, bounded failure instead of an indefinitely
     * blocked request thread. Failures surface as {@link IllegalStateException} —
     * the content package's established loud-failure posture (idempotent re-run
     * resumes after the provider recovers, see {@link DocumentEmbeddingService}).
     */
    private <T> T callWithTimeout(java.util.concurrent.Callable<T> call) {
        Future<T> pending = CALL_POOL.submit(call);
        try {
            return pending.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            pending.cancel(true);
            throw new IllegalStateException(
                    "embedding provider timed out after " + timeoutSeconds + "s");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("embedding provider call failed", cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding provider call interrupted", ie);
        }
    }

    private static GoogleGenAiTextEmbeddingModelName toModelName(String configured) {
        // "text-embedding-004" → enum TEXT_EMBEDDING_004
        String enumName = configured.replace('-', '_').replace('.', '_').toUpperCase();
        return GoogleGenAiTextEmbeddingModelName.valueOf(enumName);
    }
}
