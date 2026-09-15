package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.shared.events.TutorAnsweredEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the sprint-2 §10/§13 teacher intervention loop against
 * real Postgres, real HTTP and real RBAC:
 *
 * <pre>
 * class evidence (learners attempt)
 *        ↓
 * GET /api/v1/teacher/tests/weakness-options   — weak areas with transparent reasons
 *        ↓
 * teacher selects the weak topic
 *        ↓
 * GET /api/v1/teacher/tests/preview?topicNodeIds=...  — targeted assessment
 * </pre>
 *
 * Pins: the explicit reason derivation (LOW_MEAN_MASTERY /
 * ACTIVE_MISCONCEPTION_PRESENT — never a synthetic score), the honest
 * separation of unmeasured coverage gaps from weak topics, determinism of the
 * options and the assembled test, subject isolation (a topic's weakness never
 * leaks into another root's options), and the §11 navigation chain from class
 * evidence to a printable targeted test with answer key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class WeaknessTargetingFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** V6 seed: subject root CHM. */
    private static final UUID SUBJECT_ROOT =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    /** V6 seed: WCH11-T1.1 — carries SEED MCQs 001/002 and the seed misconception. */
    private static final UUID TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V6 seed: WCH11-T1.2 — a real leaf subtopic (its subtree is itself). */
    private static final UUID TOPIC_T1_2 =
            UUID.fromString("20000000-0000-0000-0000-000000000013");
    /** V6 seed: WCH11-T2.1 — unmeasured in this IT, carries SEED-WCH11-004/005. */
    private static final UUID TOPIC_T2_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000022");
    /** V7 seed: SEED-WCH11-001. */
    private static final UUID SEED_MCQ_1 =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: option A of 001 — wrong, tagged with the mole misconception. */
    private static final UUID SEED_MCQ_1_WRONG_TAGGED =
            UUID.fromString("41000000-0000-0000-0000-000000000001");
    /** V7 seed: option B of 001 — wrong, NO misconception tag. */
    private static final UUID SEED_MCQ_1_WRONG_PLAIN =
            UUID.fromString("41000000-0000-0000-0000-000000000002");

    @Autowired
    private AuthService authService;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private ApplicationEventPublisher events;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── helpers ─────────────────────────────────────────────────────────

    private String teacherToken() {
        String email = "weak-it-teacher-" + UUID.randomUUID().toString().substring(0, 8)
                + "@syllabai.test";
        authService.provisionUser(email, "Teacher123!", "Weakness It Teacher",
                Set.of(Role.TEACHER));
        return authService.login(new LoginRequest(email, "Teacher123!")).accessToken();
    }

    private String studentToken() {
        return authService.register(new RegisterRequest(
                "weak-it-student-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItLearner123!", "Weakness It Student")).accessToken();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "weak-it-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "Weakness It Learner")).user().id();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ── RBAC (§16 security) ─────────────────────────────────────────────

    @Test
    @DisplayName("weakness options are teacher-only: anonymous 401, student 403, teacher 200")
    void weaknessOptionsAreTeacherOnly() throws Exception {
        String path = "/api/v1/teacher/tests/weakness-options?rootId=" + SUBJECT_ROOT;

        assertThat(get(path, "").statusCode()).isEqualTo(401);

        assertThat(get(path, studentToken()).statusCode()).isEqualTo(403);

        HttpResponse<String> teacher = get(path, teacherToken());
        assertThat(teacher.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(teacher.body());
        assertThat(body.get("policy").asText()).isEqualTo("test-builder-weakness/v1");
    }

    // ── the §10/§13 intervention loop ───────────────────────────────────

    @Test
    @DisplayName("class weakness → transparent reasons → targeted test → answer key, deterministically")
    void classWeaknessBecomesTargetedTest() throws Exception {
        String teacher = teacherToken();

        // 1. class evidence: two learners measure weak on WCH11-T1.1 —
        //    learner 1 picks the misconception-tagged distractor (BDT active),
        //    learner 2 picks the plain wrong option twice (mastery low)
        UUID weakWithMisconception = newLearner();
        assessment.submit(weakWithMisconception, new SubmitAnswerRequest(
                SEED_MCQ_1, SEED_MCQ_1_WRONG_TAGGED, 20_000L, 4, false, false));
        UUID weakPlain = newLearner();
        assessment.submit(weakPlain, new SubmitAnswerRequest(
                SEED_MCQ_1, SEED_MCQ_1_WRONG_PLAIN, 20_000L, 4, false, false));
        assessment.submit(weakPlain, new SubmitAnswerRequest(
                SEED_MCQ_1, SEED_MCQ_1_WRONG_PLAIN, 20_000L, 4, false, false));

        // 2. an unmeasured topic with real activity (a tutor ask) — the honest
        //    coverage-gap lane, never claimed weak
        UUID askingLearner = newLearner();
        events.publishEvent(new TutorAnsweredEvent(
                askingLearner, "what is a covalent bond?", List.of(TOPIC_T2_1),
                3, List.of("KNOWLEDGE_NODE"), false, "openai/gpt-oss-120b",
                "tutor-grounded/v1", 700.0, Instant.now(), "EXPLANATION"));

        // 3. weakness options over HTTP: T1.1 is weak with BOTH transparent reasons
        HttpResponse<String> options = get(
                "/api/v1/teacher/tests/weakness-options?rootId=" + SUBJECT_ROOT, teacher);
        assertThat(options.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(options.body());
        assertThat(body.get("selectionHint").asText()).contains("preview");

        JsonNode weakT11 = null;
        for (JsonNode option : body.get("weakTopics")) {
            if (option.get("topicNodeId").asText().equals(TOPIC_T1_1.toString())) {
                weakT11 = option;
            }
        }
        assertThat(weakT11).as("WCH11-T1.1 must appear as a weak option").isNotNull();
        assertThat(weakT11.get("reasons").toString()).contains("LOW_MEAN_MASTERY");
        assertThat(weakT11.get("reasons").toString()).contains("ACTIVE_MISCONCEPTION_PRESENT");
        assertThat(weakT11.get("learnersMeasured").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(weakT11.get("learnersWithActiveMisconception").asInt()).isGreaterThanOrEqualTo(1);
        // WCH11-T1.1 serves 4 seed questions: 001/002 primary + 003/005
        // secondary-mapped through question_topics
        assertThat(weakT11.get("servableQuestions").asInt()).isEqualTo(4);
        // no synthetic score is exposed — the reasons are the explanation
        assertThat(weakT11.has("weaknessScore")).isFalse();

        // the unmeasured-but-active topic is an honest coverage gap, not weak
        boolean t21Weak = false;
        JsonNode gapT21 = null;
        for (JsonNode option : body.get("weakTopics")) {
            if (option.get("topicNodeId").asText().equals(TOPIC_T2_1.toString())) {
                t21Weak = true;
            }
        }
        for (JsonNode gap : body.get("coverageGaps")) {
            if (gap.get("topicNodeId").asText().equals(TOPIC_T2_1.toString())) {
                gapT21 = gap;
            }
        }
        assertThat(t21Weak).as("an unmeasured topic is NEVER claimed weak").isFalse();
        assertThat(gapT21).as("the active-but-unmeasured topic is a coverage gap").isNotNull();
        assertThat(gapT21.get("tutorEngagements").asInt()).isGreaterThanOrEqualTo(1);

        // 4. the §13 loop: the teacher targets the weak topic with the Test Builder
        HttpResponse<String> targeted = get(
                "/api/v1/teacher/tests/preview?rootId=" + SUBJECT_ROOT
                        + "&topicNodeIds=" + TOPIC_T1_1 + "&includeAnswers=true", teacher);
        assertThat(targeted.statusCode()).isEqualTo(200);
        JsonNode test = JSON.readTree(targeted.body());
        assertThat(test.get("questionCount").asInt()).isEqualTo(4);
        assertThat(test.get("totalMarks").asInt()).isEqualTo(4);   // each seed MCQ is 1 mark
        for (JsonNode q : test.get("questions")) {
            assertThat(q.get("topicCode").asText()).isEqualTo("WCH11-T1.1");
        }
        // per-topic coverage states the honest pre-cap availability
        assertThat(test.get("topics").get(0).get("servableQuestions").asInt()).isEqualTo(4);

        // 5. determinism: identical options and identical assembly on re-query
        HttpResponse<String> optionsAgain = get(
                "/api/v1/teacher/tests/weakness-options?rootId=" + SUBJECT_ROOT, teacher);
        assertThat(optionsAgain.body()).isEqualTo(options.body());
        HttpResponse<String> targetedAgain = get(
                "/api/v1/teacher/tests/preview?rootId=" + SUBJECT_ROOT
                        + "&topicNodeIds=" + TOPIC_T1_1 + "&includeAnswers=true", teacher);
        assertThat(targetedAgain.body()).isEqualTo(targeted.body());

        // 6. subject isolation: scoping to a different (leaf) node's subtree never
        //    leaks the CHM-wide class evidence into that scope's options
        HttpResponse<String> foreign = get(
                "/api/v1/teacher/tests/weakness-options?rootId=" + TOPIC_T1_2, teacher);
        assertThat(foreign.statusCode()).isEqualTo(200);
        JsonNode foreignBody = JSON.readTree(foreign.body());
        assertThat(foreignBody.get("weakTopics").toString())
                .doesNotContain(TOPIC_T1_1.toString());
        assertThat(foreignBody.get("coverageGaps").toString())
                .doesNotContain(TOPIC_T1_1.toString());
        assertThat(foreignBody.get("weakTopics").toString())
                .doesNotContain(TOPIC_T2_1.toString());

        // 7. negative: unknown topic id in the preview is simply not assembled
        //    (hard subject scope — no cross-subject hop, no error noise)
        HttpResponse<String> none = get(
                "/api/v1/teacher/tests/preview?rootId=" + SUBJECT_ROOT
                        + "&topicNodeIds=" + UUID.randomUUID(), teacher);
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(none.body()).get("questionCount").asInt()).isZero();
    }
}
