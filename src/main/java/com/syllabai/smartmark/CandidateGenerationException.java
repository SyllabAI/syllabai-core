package com.syllabai.smartmark;

/**
 * Raised when a {@link MarkingCandidateGenerator} cannot produce a usable candidate.
 * Carries a stable reason code so the persisted failure row is analysable, and —
 * when the generator DID receive provider output that could not be used — the
 * verbatim raw output, so refusal rows are self-forensic instead of requiring
 * server-log access (G-4 round 2026-09-22: the single UNPARSEABLE_OUTPUT row could
 * only be diagnosed from Render logs because the raw text was discarded here).
 */
public class CandidateGenerationException extends RuntimeException {

    public enum Reason { PROVIDER_UNAVAILABLE, UNPARSEABLE_OUTPUT, MALFORMED_ALLOCATION,
                         TRUNCATED_OUTPUT }

    private final Reason reason;
    /** Verbatim provider output associated with the refusal (null when none existed). */
    private final transient String rawOutput;

    public CandidateGenerationException(Reason reason, String message, Throwable cause) {
        this(reason, message, cause, null);
    }

    public CandidateGenerationException(Reason reason, String message, Throwable cause,
                                        String rawOutput) {
        super(message, cause);
        this.reason = reason;
        this.rawOutput = rawOutput;
    }

    public Reason reason() { return reason; }

    /** Raw provider output carried for the audit trail (null when unavailable). */
    public String rawOutput() { return rawOutput; }
}
