package com.syllabai.smartmark;

/**
 * Raised when the feedback/improvement generation surface cannot reach the LLM
 * chain (Master Spec §15 feedback actions). Mapped to 503 by the global
 * handler — the marking result itself is unaffected; the student retries the
 * explanation later.
 */
public class SmartFeedbackGenerationException extends RuntimeException {

    public SmartFeedbackGenerationException(String message) {
        super(message);
    }

    public SmartFeedbackGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
