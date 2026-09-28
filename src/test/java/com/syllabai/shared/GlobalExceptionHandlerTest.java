package com.syllabai.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.tutor.GroundedTutorGenerator;
import com.syllabai.tutor.TutorGenerationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins the client-facing error boundary's trust posture (deep-audit 09-28
 * M2/M4/M5):
 *
 * <ul>
 *   <li><strong>M2</strong> — the 503 handlers NEVER reflect the exception
 *       message: upstream provider error bodies and ops guidance stay
 *       server-side, the client gets the fixed honest text;</li>
 *   <li><strong>M4</strong> — the body-limit's mid-read abort maps to the same
 *       413 shape the filter's fast path writes;</li>
 *   <li><strong>M5</strong> — method-security denials are RETHROWN (Spring
 *       Security's documented pattern), so the ExceptionTranslationFilter
 *       produces a standard 403 instead of the catch-all's 500.</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── M2: 503 messages are fixed, never reflected ───────────────────────

    @Test
    @DisplayName("M2: a hostile TutorGenerationException message never reaches "
            + "the client body")
    void tutorMessageIsFixed() {
        TutorGenerationException hostile = new TutorGenerationException(
                "LLM chain failed: groq: generation failed (429: {\"error\":{\"message\":"
                        + "\"Rate limit for org-INTERNAL\"}})");
        ResponseEntity<ApiError> response = handler.tutorUnavailable(hostile);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("tutor_unavailable");
        assertThat(response.getBody().message())
                .isEqualTo(GroundedTutorGenerator.UNAVAILABLE_MESSAGE)
                .doesNotContain("org-INTERNAL")
                .doesNotContain("429");
    }

    @Test
    @DisplayName("M2: the smart-feedback 503 message is fixed too")
    void smartFeedbackMessageIsFixed() {
        var hostile = new com.syllabai.smartmark.SmartFeedbackGenerationException(
                "upstream body: {\"account\":\"acc-991\",\"quota\":\"...\"}");
        ResponseEntity<ApiError> response = handler.smartFeedbackUnavailable(hostile);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("smart_feedback_unavailable");
        assertThat(response.getBody().message())
                .isEqualTo("the marking feedback engine is temporarily unavailable — try again shortly")
                .doesNotContain("acc-991");
    }

    // ── M4: body-limit abort → 413 ApiError ───────────────────────────────

    @Test
    @DisplayName("M4: the streamed body-limit abort maps to 413 payload_too_large")
    void bodyTooLargeMapsTo413() {
        ResponseEntity<ApiError> response = handler.bodyTooLarge(
                new com.syllabai.http.JsonBodyLimitFilter.BodyTooLargeException(2_097_152));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error()).isEqualTo("payload_too_large");
        assertThat(response.getBody().message())
                .isEqualTo("request body exceeds the allowed size");
    }

    // ── M5: method-security denials are rethrown, not swallowed ───────────

    @Test
    @DisplayName("M5: AccessDeniedException propagates past the handler so the "
            + "security filter produces a standard 403 (never a 500)")
    void accessDeniedIsRethrown() {
        assertThatThrownBy(() -> handler.rethrowAccessDenied(
                new AccessDeniedException("Access Denied")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access Denied");
    }
}
