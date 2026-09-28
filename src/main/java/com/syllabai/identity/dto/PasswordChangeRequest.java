package com.syllabai.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self-service credential rotation (§22). The CURRENT password must be
 * presented — knowledge of the existing secret is the authorization for the
 * change, so a stolen access token alone cannot take over the account.
 *
 * @param currentPassword the password in force right now
 * @param newPassword     replaces it (BCrypt-encoded before storage)
 */
public record PasswordChangeRequest(
        @NotBlank String currentPassword,
        // same floor as registration (R6): one platform bar, no weak rotation path
        @NotBlank @Size(min = 12, max = 100)
        @Pattern(regexp = ".*\\p{L}.*", message = "must contain a letter")
        @Pattern(regexp = ".*\\d.*", message = "must contain a digit")
        String newPassword) {
}
