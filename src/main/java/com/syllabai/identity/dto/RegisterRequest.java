package com.syllabai.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-registration AND first-admin bootstrap (the bootstrap controller
 * reuses this record). The password floor is the platform's ONE documented
 * bar — the same shape BootstrapAdminService has always enforced for the
 * first admin (R6): at least 12 characters with letters and digits. An
 * 8-char no-complexity student floor made registration the weakest
 * credential path on a platform whose login is IP-budget-gated.
 *
 * <p>Role amendment (09-29, the teacher-join-code ruling): the platform's
 * self-registration path previously hardened to STUDENT-only (§6.1), which
 * left teachers with no way to self-serve — every teacher had to be
 * provisioned by an admin by hand. A TEACHER role on this request is now
 * honoured, but ONLY together with the platform's teacher join code
 * ({@code syllabai.security.teacher-join-code} / env
 * {@code SYLLABAI_TEACHER_JOIN_CODE}); the gate fails closed when the code
 * is unset, and ADMIN is never self-serviceable (the bootstrap path owns
 * that role). {@code role} and {@code joinCode} are nullable so every
 * historical 3-arg construction site (bootstrap, the flow-IT suite, the
 * prod probes) keeps compiling unchanged.</p>
 *
 * @param email       unique account email
 * @param password    raw password (BCrypt-encoded before storage)
 * @param displayName shown in the UI
 * @param role        optional requested self-service role (default STUDENT;
 *                    TEACHER requires {@code joinCode}; ADMIN refused)
 * @param joinCode    the teacher join code, required when role is TEACHER
 */
public record RegisterRequest(
        @Email @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 100)
        @Pattern(regexp = ".*\\p{L}.*", message = "must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "must contain a digit")
        String password,
        @NotBlank @Size(min = 2, max = 100) String displayName,
        String role,
        String joinCode) {

    /** The historical student-only shape — every legacy call site's shape. */
    public RegisterRequest(String email, String password, String displayName) {
        this(email, password, displayName, null, null);
    }
}
