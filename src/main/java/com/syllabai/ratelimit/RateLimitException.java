package com.syllabai.ratelimit;

/**
 * Thrown when a per-target-account login budget is exhausted (R5). Mapped to
 * 429 + Retry-After by GlobalExceptionHandler in the same ApiError shape the
 * M1 filter uses.
 */
public class RateLimitException extends RuntimeException {

    private final int retryAfterSeconds;

    public RateLimitException(int retryAfterSeconds) {
        super("Too many attempts. Wait a moment and try again.");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
