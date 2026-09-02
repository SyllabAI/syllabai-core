package com.syllabai.shared;

import java.time.Instant;

/**
 * Uniform error body for all API failures (Master Spec §22: DTOs at API boundaries).
 *
 * @param status    HTTP status code
 * @param error     short machine-readable error identifier
 * @param message   human-readable, safe to expose to clients
 * @param timestamp when the error occurred
 */
public record ApiError(int status, String error, String message, Instant timestamp) {

    static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, Instant.now());
    }
}
