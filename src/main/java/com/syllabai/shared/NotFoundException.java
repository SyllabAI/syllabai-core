package com.syllabai.shared;

/**
 * Raised when a requested resource does not exist. Mapped to HTTP 404 by
 * {@link GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(String resource, Object id) {
        super("%s %s not found".formatted(resource, id));
    }
}
