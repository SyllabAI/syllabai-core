package com.syllabai.shared;

/**
 * Raised when an operation violates a uniqueness or state constraint.
 * Mapped to HTTP 409 by {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
