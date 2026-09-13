package com.syllabai.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates domain exceptions into uniform {@link ApiError} bodies.
 * Never leaks stack traces to clients (Master Spec §20 security posture).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ExceptionHandler(com.syllabai.content.InvalidDocumentException.class)
    ResponseEntity<ApiError> invalidDocument(com.syllabai.content.InvalidDocumentException ex) {
        // full invariant list preserved — corpus operators fix a bad document in one pass
        return build(HttpStatus.BAD_REQUEST, "invalid_document", ex.getMessage());
    }
    @ExceptionHandler(com.syllabai.tutor.TutorGenerationException.class)
    ResponseEntity<ApiError> tutorUnavailable(com.syllabai.tutor.TutorGenerationException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "tutor_unavailable", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("request invalid");
        return build(HttpStatus.BAD_REQUEST, "validation_failed", detail);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> badRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", "malformed request");
    }

    // 400, not 500, when a required query parameter is absent (pilot-readiness
    // session-56 finding: /api/v1/learners/me/recommendations without rootId
    // surfaced a generic 500 — honest body, wrong status)
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "validation_failed",
                "missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "not_found", "resource not found");
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    ResponseEntity<ApiError> authentication(org.springframework.security.core.AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_credentials", "invalid credentials");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        // Full detail only in server logs; clients get an opaque 500.
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "an internal error occurred");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), error, message));
    }
}
