package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the teacher join-code signup gate (the 09-29 role
 * amendment to Master Spec §6.1) against a real Postgres and the REAL
 * property-wired service. Pins the whole self-service story:
 *
 * <ul>
 *   <li>the happy path — role=TEACHER + the configured code — lands a
 *       FUNCTIONAL teacher account (the credential logs in, and the
 *       server-issued role is the server's truth, not the request's claim);</li>
 *   <li>a wrong code and a missing code fail closed with
 *       {@link ForbiddenException} and persist nothing (a failed attempt
 *       leaves the email free — the 403 happens BEFORE any insert);</li>
 *   <li>ADMIN is never self-serviceable, even with a valid code;</li>
 *   <li>the historical 3-arg student registration is unchanged;</li>
 *   <li>duplicate email still conflicts — the gate adds no new oracle.</li>
 * </ul>
 */
@SpringBootTest(properties = "syllabai.security.teacher-join-code=it-teacher-join-7788")
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class TeacherSignupFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String CODE = "it-teacher-join-7788";
    private static final String PASSWORD = "Join-Code-Pass-1";

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("role=TEACHER + the configured code lands a functional teacher account")
    void teacherSignupHappyPath() {
        String email = "teacher.signup.it@syllabai-test.dev";
        AuthResponse response = authService.register(new RegisterRequest(
                email, PASSWORD, "IT Teacher", "TEACHER", CODE));

        assertThat(response.user().roles()).containsExactly("TEACHER");

        // the account is functional: the new credential logs in, and the
        // role comes back from the persisted row, not the request's claim
        AuthResponse login = authService.login(new LoginRequest(email, PASSWORD));
        assertThat(login.user().roles()).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("a wrong join code fails closed and persists nothing")
    void wrongCodeFailsClosed() {
        String email = "teacher.wrongcode.it@syllabai-test.dev";
        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                email, PASSWORD, "Wrong Code", "TEACHER", "not-the-code")))
                .isInstanceOf(ForbiddenException.class);

        // the refused attempt left no row — the email is still free, which
        // also pins that the 403 happens BEFORE any insert
        AuthResponse response = authService.register(new RegisterRequest(
                email, PASSWORD, "Wrong Code", "TEACHER", CODE));
        assertThat(response.user().roles()).containsExactly("TEACHER");
    }

    @Test
    @DisplayName("teacher signup without any code is the same fail-closed 403")
    void missingCodeFailsClosed() {
        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "teacher.nocode.it@syllabai-test.dev", PASSWORD, "No Code",
                "TEACHER", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("ADMIN is never self-serviceable, even with a valid code")
    void adminNeverSelfServiceable() {
        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "admin.selfserve.it@syllabai-test.dev", PASSWORD, "Sneaky Admin",
                "ADMIN", CODE)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("the historical 3-arg student registration is unchanged")
    void studentDefaultUnchanged() {
        AuthResponse response = authService.register(new RegisterRequest(
                "student.default.it@syllabai-test.dev", PASSWORD, "Default Student"));
        assertThat(response.user().roles()).containsExactly("STUDENT");
    }

    @Test
    @DisplayName("duplicate email still conflicts — the gate adds no new oracle")
    void duplicateEmailStillConflicts() {
        String email = "teacher.dup.it@syllabai-test.dev";
        authService.register(new RegisterRequest(
                email, PASSWORD, "Dup Teacher", "TEACHER", CODE));
        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                email, PASSWORD, "Dup Teacher", "TEACHER", CODE)))
                .isInstanceOf(ConflictException.class);
    }
}
