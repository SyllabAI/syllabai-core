package com.syllabai.shared;

/**
 * The request is well-formed, but the caller may not do this — the
 * fail-closed answer for a privilege-gated self-service path whose shared
 * secret the caller failed to present (or which is not configured at all).
 * Maps to HTTP 403 via {@link GlobalExceptionHandler}; deliberately the
 * SAME status whether the gate is closed for a wrong secret or because the
 * platform has disabled the path, so a client learns nothing about the
 * operator's configuration by probing.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
