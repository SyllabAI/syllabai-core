package com.syllabai.identity;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.PasswordChangeRequest;
import com.syllabai.identity.dto.RegisterRequest;
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

    private static final String JOIN_CODE = "unit-teacher-join-2026";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final com.syllabai.ratelimit.LoginAttemptBudget loginBudget =
            org.mockito.Mockito.mock(com.syllabai.ratelimit.LoginAttemptBudget.class);
    private final AuthService service =
            new AuthService(userRepository, passwordEncoder, jwtService, loginBudget, JOIN_CODE);
    private final AuthService closedGateService =
            new AuthService(userRepository, passwordEncoder, jwtService, loginBudget, "");

    private User user;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
    }

    @Test
    @DisplayName("login success clears the per-account failure history (R5)")
    void loginSuccessClearsBudget() {
        when(userRepository.findByEmailIgnoreCase("learner@syllabai.dev"))
                .thenReturn(Optional.of(user));
        when(user.enabled()).thenReturn(true);
        when(user.tokenVersion()).thenReturn(1L);
        when(user.roles()).thenReturn(java.util.Set.of(Role.STUDENT));
        when(user.email()).thenReturn("learner@syllabai.dev");
        when(user.displayName()).thenReturn("Learner");
        when(user.passwordHash()).thenReturn("current-bcrypt-hash");
        when(passwordEncoder.matches("plain", "current-bcrypt-hash")).thenReturn(true);
        when(jwtService.issueAccessToken(any())).thenReturn("jwt");

        service.login(new LoginRequest("learner@syllabai.dev", "plain"));

        verify(loginBudget).checkAllowed("learner@syllabai.dev");
        verify(loginBudget).recordSuccess("learner@syllabai.dev");
        verify(loginBudget, never()).recordFailure(anyString());
    }

    @Test
    @DisplayName("a failed login records the failure against the TARGET account (R5)")
    void loginFailureRecordedPerAccount() {
        when(userRepository.findByEmailIgnoreCase("victim@syllabai.dev"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("victim@syllabai.dev", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
        verify(loginBudget).checkAllowed("victim@syllabai.dev");
        verify(loginBudget).recordFailure("victim@syllabai.dev");
    }

    @Test
    @DisplayName("an exhausted per-account budget fails closed BEFORE credential work (R5)")
    void exhaustedBudgetFailsClosed() {
        org.mockito.Mockito.doThrow(new com.syllabai.ratelimit.RateLimitException(42))
                .when(loginBudget).checkAllowed("victim@syllabai.dev");

        assertThatThrownBy(() -> service.login(new LoginRequest("victim@syllabai.dev", "whatever")))
                .isInstanceOf(com.syllabai.ratelimit.RateLimitException.class);
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
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

    // ── teacher join-code signup (the 09-29 role amendment) ──────────────

    private void registrationSavesWhatItBuilds() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0, User.class));
        when(jwtService.issueAccessToken(any())).thenReturn("jwt");
    }

    @Test
    @DisplayName("register with role=TEACHER + the configured join code lands a TEACHER")
    void teacherSignupWithValidJoinCode() {
        registrationSavesWhatItBuilds();

        var response = service.register(new RegisterRequest(
                "new.teacher@syllabai.dev", "Join-Code-Pass-1", "New Teacher",
                "TEACHER", JOIN_CODE));

        assertThat(response.user().roles()).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("register with a WRONG join code is 403 and persists nothing")
    void teacherSignupWithWrongJoinCode() {
        registrationSavesWhatItBuilds();

        assertThatThrownBy(() -> service.register(new RegisterRequest(
                "new.teacher@syllabai.dev", "Join-Code-Pass-1", "New Teacher",
                "TEACHER", "not-the-code")))
                .isInstanceOf(com.syllabai.shared.ForbiddenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("teacher signup WITHOUT a code is the same 403 (no shape oracle)")
    void teacherSignupWithoutJoinCode() {
        registrationSavesWhatItBuilds();

        assertThatThrownBy(() -> service.register(new RegisterRequest(
                "new.teacher@syllabai.dev", "Join-Code-Pass-1", "New Teacher",
                "TEACHER", null)))
                .isInstanceOf(com.syllabai.shared.ForbiddenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an UNSET join code fails closed — every teacher signup refused")
    void teacherSignupWithUnsetCodeFailsClosed() {
        registrationSavesWhatItBuilds();

        assertThatThrownBy(() -> closedGateService.register(new RegisterRequest(
                "new.teacher@syllabai.dev", "Join-Code-Pass-1", "New Teacher",
                "TEACHER", "anything-at-all")))
                .isInstanceOf(com.syllabai.shared.ForbiddenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("ADMIN is never self-serviceable (fail-closed 403, not a downgrade)")
    void adminRoleRefused() {
        registrationSavesWhatItBuilds();

        assertThatThrownBy(() -> service.register(new RegisterRequest(
                "sneaky.admin@syllabai.dev", "Join-Code-Pass-1", "Sneaky",
                "ADMIN", JOIN_CODE)))
                .isInstanceOf(com.syllabai.shared.ForbiddenException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unknown role string is an honest 400")
    void unknownRoleIsBadRequest() {
        registrationSavesWhatItBuilds();

        assertThatThrownBy(() -> service.register(new RegisterRequest(
                "someone@syllabai.dev", "Join-Code-Pass-1", "Someone",
                "PRINCIPAL", null)))
                .isInstanceOf(com.syllabai.shared.BadRequestException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("no role on the request still lands a STUDENT — the default is unchanged")
    void defaultRoleIsStudent() {
        registrationSavesWhatItBuilds();

        var response = service.register(new RegisterRequest(
                "learner@syllabai.dev", "Join-Code-Pass-1", "Learner"));

        assertThat(response.user().roles()).containsExactly("STUDENT");
    }

    @Test
    @DisplayName("rotatePasswordHash is the only mutation path, stores the HASH, "
            + "and bumps the token version (R1: every pre-rotation token dies)")
    void rotatePasswordHashStoresHash() {
        User real = new User("ops@syllabai.dev", "old-hash", "Ops", java.util.Set.of(Role.ADMIN));
        long before = real.tokenVersion();
        real.rotatePasswordHash("new-bcrypt-hash");
        org.assertj.core.api.Assertions.assertThat(real.passwordHash()).isEqualTo("new-bcrypt-hash");
        org.assertj.core.api.Assertions.assertThat(real.email()).isEqualTo("ops@syllabai.dev");
        org.assertj.core.api.Assertions.assertThat(real.roles()).containsExactly(Role.ADMIN);
        org.assertj.core.api.Assertions.assertThat(real.tokenVersion()).isEqualTo(before + 1);
    }
}
