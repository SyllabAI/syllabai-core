package com.syllabai.identity;


import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.identity.dto.PasswordChangeRequest;
import com.syllabai.shared.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Self-service credential rotation (§22): the CURRENT password authorizes the
 * change — a stolen bearer token alone must NOT be able to take over the
 * account. This is also the supported in-product path for rotating the
 * first-admin bootstrap credential once its holder can authenticate.
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthService service =
            new AuthService(userRepository, passwordEncoder, jwtService);

    private User user;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
    }

    @Test
    @DisplayName("changePassword with the correct current password rotates the hash")
    void changePasswordHappyPath() {
        String email = "admin@syllabai.dev";
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        when(user.enabled()).thenReturn(true);
        when(user.passwordHash()).thenReturn("current-bcrypt-hash");
        when(passwordEncoder.matches("current-plain", "current-bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-passphrase-9")).thenReturn("new-bcrypt-hash");

        service.changePassword(email,
                new PasswordChangeRequest("current-plain", "new-passphrase-9"));

        verify(user).rotatePasswordHash("new-bcrypt-hash");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("changePassword with a wrong current password fails closed (no save)")
    void changePasswordWrongCurrent() {
        String email = "teacher@syllabai-test.dev";
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        when(user.enabled()).thenReturn(true);
        when(user.passwordHash()).thenReturn("current-bcrypt-hash");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(email,
                new PasswordChangeRequest("wrong-plain", "new-passphrase-9")))
                .isInstanceOf(BadCredentialsException.class);
        verify(user, never()).rotatePasswordHash(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword on a disabled account fails closed")
    void changePasswordDisabledAccount() {
        String email = "disabled@syllabai-test.dev";
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.of(user));
        when(user.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(email,
                new PasswordChangeRequest("current-plain", "new-passphrase-9")))
                .isInstanceOf(BadCredentialsException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword for an unknown user is a 404, never a probe oracle")
    void changePasswordUnknownUser() {
        String email = "ghost@syllabai-test.dev";
        when(userRepository.findByEmailIgnoreCase(email)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changePassword(email,
                new PasswordChangeRequest("current-plain", "new-passphrase-9")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("rotatePasswordHash is the only mutation path and stores the HASH")
    void rotatePasswordHashStoresHash() {
        User real = new User("ops@syllabai.dev", "old-hash", "Ops", java.util.Set.of(Role.ADMIN));
        real.rotatePasswordHash("new-bcrypt-hash");
        org.assertj.core.api.Assertions.assertThat(real.passwordHash()).isEqualTo("new-bcrypt-hash");
        org.assertj.core.api.Assertions.assertThat(real.email()).isEqualTo("ops@syllabai.dev");
        org.assertj.core.api.Assertions.assertThat(real.roles()).containsExactly(Role.ADMIN);
    }
}
