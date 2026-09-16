package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ApiException;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 failure classification at the provider/adapter boundary: SDK exception
 * TYPES and HTTP status codes map onto {@link LlmFailureClass} — never message
 * string-matching. Exercises both real SDK hierarchies (OpenAI-compatible for
 * Groq/OpenRouter, Google GenAI for Gemini) plus transport fallbacks.
 */
class LlmProviderFailureClassifierTest {

    /** The OpenAI SDK builders refuse to build without headers — empty is fine here. */
    private static final Headers HEADERS = Headers.builder().build();

    @Test
    @DisplayName("OpenAI SDK 429 → RATE_LIMITED")
    void openAiRateLimit() {
        assertThat(LlmProviderFailureClassifier.classify(
                new RateLimitException.Builder().headers(HEADERS).build()))
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
    }

    @Test
    @DisplayName("OpenAI SDK 401/403 → AUTHENTICATION_FAILURE")
    void openAiAuth() {
        assertThat(LlmProviderFailureClassifier.classify(
                new UnauthorizedException.Builder().headers(HEADERS).build()))
                .isEqualTo(LlmFailureClass.AUTHENTICATION_FAILURE);
    }

    @Test
    @DisplayName("OpenAI SDK 404 → MODEL_NOT_FOUND")
    void openAiModelNotFound() {
        assertThat(LlmProviderFailureClassifier.classify(
                new NotFoundException.Builder().headers(HEADERS).build()))
                .isEqualTo(LlmFailureClass.MODEL_NOT_FOUND);
    }

    @Test
    @DisplayName("OpenAI SDK 400 → BAD_REQUEST")
    void openAiBadRequest() {
        assertThat(LlmProviderFailureClassifier.classify(
                new BadRequestException.Builder().headers(HEADERS).build()))
                .isEqualTo(LlmFailureClass.BAD_REQUEST);
    }

    @Test
    @DisplayName("OpenAI SDK 5xx → PROVIDER_UNAVAILABLE")
    void openAiServer() {
        assertThat(LlmProviderFailureClassifier.classify(
                new InternalServerException.Builder().headers(HEADERS).statusCode(500).build()))
                .isEqualTo(LlmFailureClass.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("OpenAI SDK malformed payload → INVALID_RESPONSE")
    void openAiInvalidData() {
        assertThat(LlmProviderFailureClassifier.classify(
                new OpenAIInvalidDataException("unparseable body")))
                .isEqualTo(LlmFailureClass.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("GenAI SDK status codes map by HTTP semantics")
    void genAiStatusCodes() {
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(429, "RESOURCE_EXHAUSTED", "quota")))
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(403, "PERMISSION_DENIED", "bad key")))
                .isEqualTo(LlmFailureClass.AUTHENTICATION_FAILURE);
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(404, "NOT_FOUND", "no such model")))
                .isEqualTo(LlmFailureClass.MODEL_NOT_FOUND);
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(400, "INVALID_ARGUMENT", "bad request")))
                .isEqualTo(LlmFailureClass.BAD_REQUEST);
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(500, "INTERNAL", "boom")))
                .isEqualTo(LlmFailureClass.PROVIDER_UNAVAILABLE);
        assertThat(LlmProviderFailureClassifier.classify(
                new ApiException(504, "DEADLINE_EXCEEDED", "slow")))
                .isEqualTo(LlmFailureClass.TIMEOUT);
    }

    @Test
    @DisplayName("transport timeouts → TIMEOUT")
    void transportTimeouts() {
        assertThat(LlmProviderFailureClassifier.classify(new TimeoutException("call timed out")))
                .isEqualTo(LlmFailureClass.TIMEOUT);
        assertThat(LlmProviderFailureClassifier.classify(
                new java.net.SocketTimeoutException("read timed out")))
                .isEqualTo(LlmFailureClass.TIMEOUT);
    }

    @Test
    @DisplayName("classification walks the cause chain to the recognisable root")
    void causeChainIsWalked() {
        RuntimeException wrapped = new RuntimeException("async boundary",
                new ApiException(429, "RESOURCE_EXHAUSTED", "quota"));
        assertThat(LlmProviderFailureClassifier.classify(wrapped))
                .isEqualTo(LlmFailureClass.RATE_LIMITED);
    }

    @Test
    @DisplayName("an already-classified LlmProviderException keeps its class")
    void classifiedExceptionIsPreserved() {
        LlmProviderException classified = new LlmProviderException("groq",
                "generation timed out", null, LlmFailureClass.TIMEOUT);
        assertThat(LlmProviderFailureClassifier.classify(classified))
                .isEqualTo(LlmFailureClass.TIMEOUT);
    }

    @Test
    @DisplayName("unrecognisable failures stay UNKNOWN (conservative default)")
    void unknownStaysUnknown() {
        assertThat(LlmProviderFailureClassifier.classify(new IllegalStateException("boom")))
                .isEqualTo(LlmFailureClass.UNKNOWN);
        assertThat(LlmProviderFailureClassifier.classify(null))
                .isEqualTo(LlmFailureClass.UNKNOWN);
    }

    @Test
    @DisplayName("configuration failures are exactly AUTHENTICATION_FAILURE and MODEL_NOT_FOUND")
    void configurationFailureSet() {
        assertThat(LlmFailureClass.AUTHENTICATION_FAILURE.isConfigurationFailure()).isTrue();
        assertThat(LlmFailureClass.MODEL_NOT_FOUND.isConfigurationFailure()).isTrue();
        for (LlmFailureClass failureClass : LlmFailureClass.values()) {
            if (failureClass != LlmFailureClass.AUTHENTICATION_FAILURE
                    && failureClass != LlmFailureClass.MODEL_NOT_FOUND) {
                assertThat(failureClass.isConfigurationFailure())
                        .as("%s must be transient", failureClass).isFalse();
            }
        }
    }
}
