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
 * @param email       unique account email
 * @param password    raw password (BCrypt-encoded before storage)
 * @param displayName shown in the UI
 */
public record RegisterRequest(
        @Email @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 100)
        @Pattern(regexp = ".*\\p{L}.*", message = "must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "must contain a digit")
        String password,
        @NotBlank @Size(min = 2, max = 100) String displayName) {
}
