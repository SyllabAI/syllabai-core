package com.syllabai.infrastructure.llm;

import com.fasterxml.jackson.core.JacksonException;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies SDK/HTTP exceptions into {@link LlmFailureClass} at the provider/adapter
 * boundary (ADR-023). Type- and status-driven — never string-matching on exception
 * messages:
 *
 * <ul>
 *   <li>OpenAI-compatible providers (Groq, OpenRouter) surface the official
 *       {@code com.openai.errors} hierarchy: {@link OpenAIServiceException} subclasses
 *       carry {@code statusCode()} (429 rate limit, 401/403 auth, 404 model, 400/422
 *       request, 5xx server).</li>
 *   <li>Gemini surfaces {@code com.google.genai.errors.ApiException} with
 *       {@code code()} carrying the HTTP-ish status.</li>
 *   <li>Transport-level JDK exceptions (timeouts, connect failures) map to
 *       TIMEOUT / PROVIDER_UNAVAILABLE.</li>
 * </ul>
 *
 * The classifier never inspects provider error TEXT for credentials — it only reads
 * exception types and numeric status codes, so classified messages stay safe for the
 * aggregate chain error and the admin health output.
 */
public final class LlmProviderFailureClassifier {

    private LlmProviderFailureClassifier() {
    }

    /**
     * Classify the exception (and, when it wraps one, its cause chain) into a
     * {@link LlmFailureClass}. Never throws — worst case {@link LlmFailureClass#UNKNOWN}.
     */
    public static LlmFailureClass classify(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 10) {
            LlmFailureClass classified = classifyOne(current);
            if (classified != LlmFailureClass.UNKNOWN) {
                return classified;
            }
            current = current.getCause();
        }
        return LlmFailureClass.UNKNOWN;
    }

    private static LlmFailureClass classifyOne(Throwable failure) {
        // ── our own adapter-level timeout (callWithTimeout) ────────────────────
        if (failure instanceof LlmProviderException providerException) {
            return providerException.failureClass();
        }
        if (failure instanceof TimeoutException || failure instanceof HttpTimeoutException
                || failure instanceof SocketTimeoutException) {
            return LlmFailureClass.TIMEOUT;
        }

        // ── OpenAI-compatible SDK hierarchy (Groq, OpenRouter) ─────────────────
        if (failure instanceof OpenAIServiceException serviceException) {
            return byHttpStatus(serviceException.statusCode());
        }
        if (failure instanceof OpenAIIoException) {
            return LlmFailureClass.PROVIDER_UNAVAILABLE;
        }
        if (failure instanceof OpenAIInvalidDataException) {
            // malformed/unparseable provider payload (outside the HTTP hierarchy)
            return LlmFailureClass.INVALID_RESPONSE;
        }
        if (failure instanceof OpenAIException) {
            return LlmFailureClass.PROVIDER_UNAVAILABLE;
        }

        // ── Google GenAI SDK hierarchy (Gemini) ────────────────────────────────
        if (failure instanceof ApiException apiException) {
            return byHttpStatus(apiException.code());
        }
        if (failure instanceof GenAiIOException) {
            return LlmFailureClass.PROVIDER_UNAVAILABLE;
        }

        // ── transport / parsing fallbacks ──────────────────────────────────────
        if (failure instanceof ConnectException || failure instanceof java.io.IOException) {
            return LlmFailureClass.PROVIDER_UNAVAILABLE;
        }
        if (failure instanceof JacksonException) {
            return LlmFailureClass.INVALID_RESPONSE;
        }
        return LlmFailureClass.UNKNOWN;
    }

    /** HTTP-status mapping shared by both SDK hierarchies. */
    private static LlmFailureClass byHttpStatus(int status) {
        if (status == 429) {
            return LlmFailureClass.RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return LlmFailureClass.AUTHENTICATION_FAILURE;
        }
        if (status == 404) {
            return LlmFailureClass.MODEL_NOT_FOUND;
        }
        if (status == 400 || status == 422) {
            return LlmFailureClass.BAD_REQUEST;
        }
        if (status == 408 || status == 504) {
            return LlmFailureClass.TIMEOUT;
        }
        if (status >= 500) {
            return LlmFailureClass.PROVIDER_UNAVAILABLE;
        }
        return LlmFailureClass.UNKNOWN;
    }
}
