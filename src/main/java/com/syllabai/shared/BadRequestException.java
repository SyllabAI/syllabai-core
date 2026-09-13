package com.syllabai.shared;

/**
 * A syntactically present but semantically invalid request element (unknown
 * enum value in a filter, malformed parameter value). Maps to HTTP 400 via
 * {@link GlobalExceptionHandler} — distinct from {@link NotFoundException},
 * which is reserved for a well-formed request referencing something that
 * does not exist.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
