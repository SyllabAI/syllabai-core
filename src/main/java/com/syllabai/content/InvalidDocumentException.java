package com.syllabai.content;

/**
 * Thrown when a canonical document fails core-side validation (T-013). Mapped to
 * HTTP 400 with the full invariant list preserved — corpus operators need the exact
 * violations, not an opaque "malformed request".
 */
public class InvalidDocumentException extends RuntimeException {

    public InvalidDocumentException(String message) {
        super(message);
    }
}
