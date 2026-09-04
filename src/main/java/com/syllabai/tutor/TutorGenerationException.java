package com.syllabai.tutor;

/**
 * Raised when grounded generation cannot run — typically no LLM provider
 * configured (zero API keys) or every provider in the free chain failing.
 * KA-RAG refuses rather than answering ungrounded (Master Spec §13).
 */
public class TutorGenerationException extends RuntimeException {

    public TutorGenerationException(String message) {
        super(message);
    }

    public TutorGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
