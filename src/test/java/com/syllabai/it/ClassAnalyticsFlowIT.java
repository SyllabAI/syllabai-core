package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.learner.LearnerKnowledgeGraphService;
import com.syllabai.teacher.TestBuilderService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
 * Integration test: the teacher class-intelligence read model (productization
 * sprint 2 §2–§5) over real Postgres with the V6/V7 seed and real HTTP — the
 * whole insight chain the sprint mandates:
 * <em>student attempts → learning evidence → learner state → class-level
 * aggregation → teacher insight → intervention</em>.
 *
 * <p>Proves, against the real database and the real security filter chain:
 * (1) RBAC — anonymous 401, STUDENT 403, TEACHER 200 on every class endpoint;
 * (2) the class overview aggregates REAL evidence (one misconception-tagged
 * wrong answer, one correct answer, one silent learner) with evidence kinds
 * separated — the misconception appears as an active signal, the silent
 * learner stays honestly UNMEASURED with a null mean; (3) the learner list
 * carries per-learner rows with the BDT misconception signal and recent
 * attempt counts; (4) the topic drill-down returns the affected learner with
 * a reason, the representative raw attempt evidence, the prerequisite chain
 * and the servable validated questions; (5) the intervention leg closes the
 * loop — the SAME topic handed to the Test Builder assembles those validated
 * questions into an assessment; (6) teacher reads never mutate learner state
 * (the BDT probability is unchanged after every aggregation); (7) subject
 * isolation — a topic outside the root is a 404, never a silent hop.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ClassAnalyticsFlowIT {

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
    /** V6 seed: WCH11-T1.1 (mole calculations) — carries the seed MCQs. */
    private static final UUID TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V6 seed: MIS-T1.1-01 attached to WCH11-T1.1. */
    private static final UUID MIS_T1_1_01 =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    /** V7 seed: SEED-WCH11-001 (mass of 0.25 mol CaCO3). */
    private static final UUID SEED_MCQ =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: option A "0.25 g" — wrong, tagged with MIS-T1.1-01. */
    private static final UUID SEED_MCQ_WRONG_OPTION =
            UUID.fromString("41000000-0000-0000-0000-000000000001");
    /** V7 seed: option C "25.0 g" — the correct answer. */
    private static final UUID SEED_MCQ_CORRECT_OPTION =
            UUID.fromString("41000000-0000-0000-0000-000000000003");

    @Autowired
    private AuthService authService;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private TestBuilderService testBuilder;
    @Autowired
    private LearnerKnowledgeGraphService learnerGraph;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── helpers ─────────────────────────────────────────────────────────

    private String teacherToken() {
        String email = "class-it-teacher-" + UUID.randomUUID().toString().substring(0, 8)
                + "@syllabai.test";
        authService.provisionUser(email, "Teacher123!", "Class It Teacher", Set.of(Role.TEACHER));
        return authService.login(new LoginRequest(email, "Teacher123!")).accessToken();
    }

    private String studentToken() {
        return authService.register(new RegisterRequest(
                "class-it-student-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItLearner123!", "Class It Student")).accessToken();
    }

    private UUID newLearner(String name) {
        return authService.register(new RegisterRequest(
                "class-it-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", name)).user().id();
    }

    private JsonNode get(String path, String token) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + path + " -> " + response.statusCode()
                    + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private int getStatus(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    // ── RBAC (§13): the class surface is teacher-only ───────────────────

    @Test
    @DisplayName("RBAC: anonymous 401, STUDENT 403, TEACHER 200 on every class-analytics endpoint")
    void classSurfaceIsTeacherOnly() throws Exception {
        String teacher = teacherToken();
        String student = studentToken();
        String drillDown = "/api/v1/teacher/class/topics/" + TOPIC_T1_1
                + "/drill-down?rootId=" + SUBJECT_ROOT;

        // anonymous: 401, never class data
        assertThat(getStatus("/api/v1/teacher/class/overview?rootId=" + SUBJECT_ROOT, null))
                .isEqualTo(401);
        assertThat(getStatus("/api/v1/teacher/class/learners?rootId=" + SUBJECT_ROOT, null))
                .isEqualTo(401);
        assertThat(getStatus(drillDown, null)).isEqualTo(401);

        // student: 403 on every endpoint (token proven valid by registration)
        assertThat(getStatus("/api/v1/teacher/class/overview?rootId=" + SUBJECT_ROOT, student))
                .isEqualTo(403);
        assertThat(getStatus("/api/v1/teacher/class/learners?rootId=" + SUBJECT_ROOT, student))
                .isEqualTo(403);
        assertThat(getStatus(drillDown, student)).isEqualTo(403);

        // teacher: 200 on every endpoint
        assertThat(getStatus("/api/v1/teacher/class/overview?rootId=" + SUBJECT_ROOT, teacher))
                .isEqualTo(200);
        assertThat(getStatus("/api/v1/teacher/class/learners?rootId=" + SUBJECT_ROOT, teacher))
                .isEqualTo(200);
        assertThat(getStatus(drillDown, teacher)).isEqualTo(200);
    }

    // ── the full insight chain (§2–§5) ──────────────────────────────────

    @Test
    @DisplayName("attempts → evidence → class aggregation → insight → intervention, read-only")
    void fullClassIntelligenceChain() throws Exception {
        // three real learners: one wrong misconception-tagged answer, one
        // correct answer, one silent — the honest-empty state must survive
        UUID weak = newLearner("Ita Weak");
        UUID strong = newLearner("Bob Strong");
        UUID silent = newLearner("Cara Silent");
        assessment.submit(weak, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_WRONG_OPTION, 25_000L, 4, false, false));
        assessment.submit(strong, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_CORRECT_OPTION, 18_000L, 4, false, false));

        String teacher = teacherToken();

        // 1. overview: cohort + evidence reach + the T1.1 heatmap cell
        JsonNode overview = get("/api/v1/teacher/class/overview?rootId=" + SUBJECT_ROOT, teacher);
        assertThat(overview.path("policy").asText()).isEqualTo("class-analytics/v1");
        assertThat(overview.path("enrolledLearners").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(overview.path("learnersWithEvidence").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(overview.path("learnersRecentlyActive").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode cell = findTopic(overview.path("topics"), TOPIC_T1_1);
        assertThat(cell.path("code").asText()).isEqualTo("WCH11-T1.1");
        assertThat(cell.path("learnersMeasured").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(cell.path("meanMastery").isNumber()).isTrue();
        assertThat(cell.path("meanMastery").asDouble()).isBetween(0.0, 1.0);
        // the misconception-tagged wrong answer is an ACTIVE signal for exactly
        // one learner (BDT 0.75 ≥ 0.5); the correct answer is not
        assertThat(cell.path("learnersWithActiveMisconception").asInt()).isEqualTo(1);
        assertThat(cell.path("activeMisconceptionSignals").asInt()).isEqualTo(1);
        // servable validated questions on the topic (seed MCQs 001/002)
        assertThat(cell.path("servableQuestions").asInt()).isGreaterThanOrEqualTo(2);
        // recent attempts in the 14-day window: both submits
        assertThat(overview.path("recentActivity").path("recentAttempts").asInt())
                .isGreaterThanOrEqualTo(2);

        // 2. learner list: evidence-separated rows, honest unknowns
        JsonNode learners = get("/api/v1/teacher/class/learners?rootId=" + SUBJECT_ROOT, teacher);
        JsonNode weakRow = findLearner(learners, weak);
        JsonNode strongRow = findLearner(learners, strong);
        JsonNode silentRow = findLearner(learners, silent);

        assertThat(weakRow.path("evidenceState").asText()).isEqualTo("MEASURED");
        assertThat(weakRow.path("recentAttempts").asInt()).isEqualTo(1);
        assertThat(weakRow.path("recentCorrect").asInt()).isEqualTo(0);
        assertThat(weakRow.path("lastActivityAt").isTextual()).isTrue();
        assertThat(weakRow.path("activeMisconceptions").asInt()).isEqualTo(1);
        JsonNode signal = weakRow.path("misconceptionSignals").get(0);
        assertThat(signal.path("code").asText()).isEqualTo("MIS-T1.1-01");
        assertThat(signal.path("probability").asDouble()).isEqualTo(0.75);
        assertThat(signal.path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(signal.path("parentTopicCode").asText()).isEqualTo("WCH11-T1.1");

        assertThat(strongRow.path("evidenceState").asText()).isEqualTo("MEASURED");
        assertThat(strongRow.path("recentCorrect").asInt()).isEqualTo(1);
        assertThat(strongRow.path("activeMisconceptions").asInt()).isZero();

        // the silent learner: honestly UNMEASURED — null mastery, no fabrication
        assertThat(silentRow.path("evidenceState").asText()).isEqualTo("UNMEASURED");
        assertThat(silentRow.path("meanMastery").isNull()).isTrue();
        assertThat(silentRow.path("activeMisconceptions").asInt()).isZero();
        assertThat(silentRow.path("tutorEngagements").asInt()).isZero();

        // 3. drill-down: topic → affected learners → evidence → questions
        JsonNode drill = get("/api/v1/teacher/class/topics/" + TOPIC_T1_1
                + "/drill-down?rootId=" + SUBJECT_ROOT, teacher);
        assertThat(drill.path("topic").path("nodeId").asText()).isEqualTo(TOPIC_T1_1.toString());

        // the weak learner is affected by an ACTIVE misconception (1 attempt is
        // below the weakness evidence floor of 2 — mastery alone cannot claim)
        JsonNode affected = findAffected(drill.path("affectedLearners"), weak);
        assertThat(affected.path("reason").asText()).isEqualTo("ACTIVE_MISCONCEPTION");
        assertThat(affected.path("misconceptions").get(0).path("code").asText())
                .isEqualTo("MIS-T1.1-01");

        // representative evidence: the raw attempt, inspectable before intervening
        JsonNode evidence = drill.path("representativeEvidence");
        assertThat(evidence.size()).isGreaterThanOrEqualTo(2);
        JsonNode weakEvidence = findEvidence(evidence, weak);
        assertThat(weakEvidence.path("correct").asBoolean()).isFalse();
        assertThat(weakEvidence.path("questionRef").asText()).isEqualTo("SEED-WCH11-001");
        assertThat(weakEvidence.path("markingState").asText()).isEqualTo("AUTO_GRADED");

        // the prerequisite chain: honest unmeasured links (no evidence there yet)
        for (JsonNode link : drill.path("prerequisiteChain")) {
            assertThat(link.path("masteryBand").asText()).isEqualTo("UNMEASURED");
            assertThat(link.path("meanMastery").isNull()).isTrue();
        }

        // servable validated questions for remediation on exactly this topic
        List<String> refs = new ArrayList<>();
        drill.path("servableQuestions").forEach(q -> refs.add(q.path("externalRef").asText()));
        assertThat(refs).contains("SEED-WCH11-001", "SEED-WCH11-002");

        // 4. intervention leg: the SAME topic through the Test Builder
        var preview = testBuilder.preview(SUBJECT_ROOT, List.of(TOPIC_T1_1), 20, null, true);
        assertThat(preview.questions()).isNotEmpty();
        assertThat(preview.questions().stream().map(TestBuilderService.TestQuestionView::id)
                .toList()).contains(SEED_MCQ);

        // 5. read-only proof: teacher aggregation never mutated learner state
        var weakGraph = learnerGraph.graphFor(weak, SUBJECT_ROOT);
        weakGraph.nodes().stream()
                .filter(n -> MIS_T1_1_01.equals(n.id()))
                .forEach(n -> {
                    assertThat(n.misconceptionProbability()).isEqualTo(0.75);
                    assertThat(n.misconceptionActive()).isTrue();
                });

        // 6. subject isolation: a topic outside this root is a 404
        assertThat(getStatus("/api/v1/teacher/class/topics/" + UUID.randomUUID()
                + "/drill-down?rootId=" + SUBJECT_ROOT, teacher)).isEqualTo(404);
    }

    // ── json lookups ────────────────────────────────────────────────────

    private JsonNode findTopic(JsonNode topics, UUID nodeId) {
        for (JsonNode t : topics) {
            if (nodeId.toString().equals(t.path("nodeId").asText())) {
                return t;
            }
        }
        throw new IllegalStateException("topic " + nodeId + " missing from response");
    }

    private JsonNode findLearner(JsonNode rows, UUID learnerId) {
        for (JsonNode r : rows) {
            if (learnerId.toString().equals(r.path("learnerId").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("learner " + learnerId + " missing from response");
    }

    private JsonNode findAffected(JsonNode rows, UUID learnerId) {
        for (JsonNode r : rows) {
            if (learnerId.toString().equals(r.path("learnerId").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("affected learner " + learnerId + " missing");
    }

    private JsonNode findEvidence(JsonNode rows, UUID learnerId) {
        for (JsonNode r : rows) {
            if (learnerId.toString().equals(r.path("learnerId").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("evidence for learner " + learnerId + " missing");
    }
}
