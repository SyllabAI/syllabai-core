package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-029 authorization regression (audit-mandated): the teacher review surface
 * is protected by the BACKEND route rule, not by frontend role hiding.
 *
 * <p>Proves over real HTTP (JDK HttpClient against the embedded server — real
 * socket, real JwtAuthenticationFilter, real Spring Security filter chain) with
 * real JWTs: anonymous and forged tokens get 401; a STUDENT token — proven
 * valid on its own learner surface first, so the 403s below cannot pass
 * vacuously — is denied on every teacher endpoint (roster, marking queue,
 * marking detail, Smart Mark invocation, human-mark override, κ-gate read and
 * evaluation); TEACHER and ADMIN tokens are served. This is the executable
 * proof of the SecurityConfig rule
 * {@code /api/v1/teacher/** -> hasAnyRole(TEACHER, ADMIN)} (Master Spec §20:
 * server-side authorization; frontend role UI is not sufficient).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class TeacherRouteSecurityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private AuthService authService;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
    }

    private record Actors(String studentToken, String teacherToken, String adminToken,
                          String studentName) {
    }

    private Actors actors() {
        // self-registration always creates a STUDENT (Master Spec §6.1)
        String name = "Sec Learner " + UUID.randomUUID().toString().substring(0, 4);
        AuthResponse student = authService.register(new RegisterRequest(
                uniqueEmail("sec-student"), "Student123!", name));
        String teacherEmail = uniqueEmail("sec-teacher");
        authService.provisionUser(teacherEmail, "Teacher123!", "Sec Teacher",
                Set.of(Role.TEACHER));
        AuthResponse teacher = authService.login(new LoginRequest(teacherEmail, "Teacher123!"));
        String adminEmail = uniqueEmail("sec-admin");
        authService.provisionUser(adminEmail, "Admin1234!", "Sec Admin", Set.of(Role.ADMIN));
        AuthResponse admin = authService.login(new LoginRequest(adminEmail, "Admin1234!"));
        return new Actors(student.accessToken(), teacher.accessToken(), admin.accessToken(), name);
    }

    /** performs one HTTP call; token/body are optional */
    private Response call(String method, String path, String token, String jsonBody) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + path))
                    .header("Accept", "application/json");
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (jsonBody != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + path, e);
        }
    }

    private record Response(int status, String body) {
    }

    @Test
    @DisplayName("a STUDENT token is valid on its own surface yet 403 on every teacher endpoint")
    void studentCannotReachAnyTeacherSurface() {
        Actors a = actors();
        UUID anyAnswer = UUID.randomUUID();

        // control: the student token itself WORKS — otherwise the 403s below
        // would pass vacuously on a broken token
        Response ownState = call("GET", "/api/v1/learners/me/state", a.studentToken(), null);
        assertThat(ownState.status()).isEqualTo(200);

        Response roster = call("GET", "/api/v1/teacher/learners", a.studentToken(), null);
        assertThat(roster.status()).isEqualTo(403);

        Response queue = call("GET", "/api/v1/teacher/marking/answers?state=PENDING",
                a.studentToken(), null);
        assertThat(queue.status()).isEqualTo(403);

        Response detail = call("GET", "/api/v1/teacher/marking/answers/" + anyAnswer,
                a.studentToken(), null);
        assertThat(detail.status()).isEqualTo(403);

        Response smartMark = call("POST",
                "/api/v1/teacher/marking/answers/" + anyAnswer + "/smart-mark",
                a.studentToken(), null);
        assertThat(smartMark.status()).isEqualTo(403);

        Response humanMark = call("POST",
                "/api/v1/teacher/marking/answers/" + anyAnswer + "/human-mark",
                a.studentToken(), "{\"marksAwarded\":1}");
        assertThat(humanMark.status()).isEqualTo(403);

        Response kappaLatest = call("GET", "/api/v1/teacher/marking/kappa/latest",
                a.studentToken(), null);
        assertThat(kappaLatest.status()).isEqualTo(403);

        Response kappaEvaluate = call("POST", "/api/v1/teacher/marking/kappa/evaluate",
                a.studentToken(), "{}");
        assertThat(kappaEvaluate.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("anonymous requests and forged/garbage tokens get 401, never teacher data")
    void anonymousAndForgedTokensAreRejected() {
        Response anonymous = call("GET", "/api/v1/teacher/learners", null, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("@");

        Response forged = call("GET", "/api/v1/teacher/learners", "this-is-not-a-jwt", null);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body()).doesNotContain("@");

        // a structurally valid-looking but wrongly signed token is equally rejected
        Response random = call("GET", "/api/v1/teacher/marking/kappa/latest",
                "eyJhbGciOiJIUzI1NiJ9.forged.payload.signature", null);
        assertThat(random.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("TEACHER and ADMIN tokens reach the roster and queue over real HTTP")
    void teacherAndAdminReachTheTeacherSurface() {
        Actors a = actors();

        Response roster = call("GET", "/api/v1/teacher/learners", a.teacherToken(), null);
        assertThat(roster.status()).isEqualTo(200);
        assertThat(roster.body()).contains(a.studentName());

        Response queue = call("GET", "/api/v1/teacher/marking/answers?state=PENDING",
                a.teacherToken(), null);
        assertThat(queue.status()).isEqualTo(200);
        assertThat(queue.body()).startsWith("[");

        Response adminRoster = call("GET", "/api/v1/teacher/learners", a.adminToken(), null);
        assertThat(adminRoster.status()).isEqualTo(200);
        assertThat(adminRoster.body()).contains(a.studentName());
    }
}
