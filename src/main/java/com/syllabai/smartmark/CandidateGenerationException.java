package com.syllabai.smartmark;

/**
 * Raised when a {@link MarkingCandidateGenerator} cannot produce a usable candidate.
 * Carries a stable reason code so the persisted failure row is analysable.
 */
public class CandidateGenerationException extends RuntimeException {

    public enum Reason { PROVIDER_UNAVAILABLE, UNPARSEABLE_OUTPUT, MALFORMED_ALLOCATION }

    private final Reason reason;

    public CandidateGenerationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
