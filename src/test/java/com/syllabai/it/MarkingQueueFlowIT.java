package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredAttemptResultView;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Integration test: the sprint-2 §6/§7 marking-throughput lane and review-queue
 * intelligence over real Postgres with real HTTP — the surfaces a teacher works
 * the 243-deep pending queue through.
 *
 * <p>Proves against the real database and the real security filter chain:
 * (1) RBAC — anonymous 401, STUDENT 403, TEACHER 200 on queue-v2, throughput,
 * review-queue-v3 and the smart-mark batch; (2) the deterministic paper-grouped
 * queue (oldest-waiting paper first, groups contiguous, mark→next chain) and
 * its stability across calls; (3) the bounded batch with honest per-item
 * outcomes — MARKED / FAILED / SKIPPED_ALREADY_MARKED side by side, partial
 * success preserved, no gate weakened; (4) throughput metrics that count what
 * happened (state mix, 24h human-mark window, oldest pending age); (5) the v3
 * review queue carrying the §7 signals and rank reasons over real ingested
 * content, deterministically ordered.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class MarkingQueueFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private AuthService authService;
    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private TeacherMarkingService markingService;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private AnswerRepository answers;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── helpers ─────────────────────────────────────────────────────────

    private String teacherToken() {
        return teacher().accessToken();
    }

    private com.syllabai.identity.dto.AuthResponse teacher() {
        String email = "mqueue-it-teacher-" + UUID.randomUUID().toString().substring(0, 8)
                + "@syllabai.test";
        authService.provisionUser(email, "Teacher123!", "MQueue It Teacher",
                Set.of(Role.TEACHER));
        return authService.login(new LoginRequest(email, "Teacher123!"));
    }

    private String studentToken() {
        return authService.register(new RegisterRequest(
                "mqueue-it-student-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItLearner123!", "MQueue It Student")).accessToken();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "mqueue-it-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "MQueue Learner")).user().id();
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

    private int postStatus(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
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

    private JsonNode post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Accept", "application/json")
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("POST " + path + " -> " + response.statusCode()
                    + ": " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static PastPaperDraftDto draft(String paperCode) {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2013-" + UUID.randomUUID().toString().substring(0, 6),
                        paperCode, "it-qp-doc", "it-ms-doc"),
                List.of(new PastPaperDraftDto.QuestionDraft("q1", "1", "Question 1 stem",
                        "Explain", 2, "STRUCTURED", 1, 0.6,
                        List.of(new PastPaperDraftDto.PartDraft("a", "Part a prompt",
                                "State", 2, 0.6)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "it-ms-doc", List.of(
                        new PastPaperDraftDto.MarkPointDraft("1-a", 1, "the correct content", 2,
                                List.of(), 0.6))),
                "it-test-method",
                true);
    }

    /** one validated paper with one markable structured part */
    private record MarkablePaper(UUID paperId, Question question, QuestionVersion version,
                                 UUID partId, MarkScheme scheme) {
    }

    private MarkablePaper ingestAndValidate(String paperCode) {
        var summary = ingestion.ingest(draft(paperCode), UUID.randomUUID());
        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        review.validateQuestionVersion(version.id());
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
        return new MarkablePaper(summary.paperId(), question, version,
                version.parts().get(0).id(), scheme);
    }

    private UUID submitAnswer(MarkablePaper paper, String content) {
        StructuredAttemptResultView result = assessment.submitStructured(newLearner(),
                new StructuredSubmitRequest(paper.question().id(),
                        List.of(new PartAnswerRequest(paper.partId(), content)),
                        20000L, 3, false, true));
        return answers.findByAttemptIdOrderByQuestionPartId(result.attemptId())
                .get(0).id();
    }

    // ── RBAC (§13): the throughput lane is teacher-only ─────────────────

    @Test
    @DisplayName("RBAC: anonymous 401, STUDENT 403, TEACHER 200 on queue-v2, throughput, queue-v3, batch")
    void throughputLaneIsTeacherOnly() throws Exception {
        String teacher = teacherToken();
        String student = studentToken();
        String batch = "{\"answerIds\":[]}";
        String unknownBatch = "{\"answerIds\":[\"" + UUID.randomUUID() + "\"]}";

        for (String path : List.of("/api/v1/teacher/marking/queue-v2",
                "/api/v1/teacher/marking/throughput",
                "/api/v1/teacher/content/review-queue-v3")) {
            assertThat(getStatus(path, null)).isEqualTo(401);
            assertThat(getStatus(path, student)).isEqualTo(403);
            assertThat(getStatus(path, teacher)).isEqualTo(200);
        }
        assertThat(postStatus("/api/v1/teacher/marking/smart-mark-batch", null, batch))
                .isEqualTo(401);
        assertThat(postStatus("/api/v1/teacher/marking/smart-mark-batch", student, batch))
                .isEqualTo(403);
        // teacher: malformed/empty batch is a 400 from the controller (reached),
        // a valid body with an unknown id is 200 with an honest FAILED item
        assertThat(postStatus("/api/v1/teacher/marking/smart-mark-batch", teacher, batch))
                .isEqualTo(400);
        JsonNode view = post("/api/v1/teacher/marking/smart-mark-batch",
                teacher, unknownBatch);
        assertThat(view.get("items").get(0).get("outcome").asText()).isEqualTo("FAILED");
    }

    // ── the deterministic queue + batch + throughput over real data ─────

    @Test
    @DisplayName("paper-grouped queue → mark&next → bounded batch (partial success) → throughput counts")
    void fullThroughputLane() throws Exception {
        String teacher = teacherToken();

        // paper B gets the OLDEST waiting learner answer; paper A gets two
        MarkablePaper paperB = ingestAndValidate("4MQ0/2C");
        MarkablePaper paperA = ingestAndValidate("4MQ0/1C");
        UUID bAnswer = submitAnswer(paperB, "the correct content");
        UUID aAnswer1 = submitAnswer(paperA, "");
        UUID aAnswer2 = submitAnswer(paperA, "partial");

        // 1. queue-v2: deterministic paper grouping — B (oldest waiting) first
        JsonNode queue = get("/api/v1/teacher/marking/queue-v2?state=PENDING", teacher);
        assertThat(queue.get("state").asText()).isEqualTo("PENDING");
        assertThat(queue.get("groups").size()).isEqualTo(2);
        assertThat(queue.get("groups").get(0).get("paperId").asText())
                .isEqualTo(paperB.paperId().toString());
        assertThat(queue.get("groups").get(0).get("count").asInt()).isEqualTo(1);
        assertThat(queue.get("groups").get(0).get("oldestPendingAt").isNull()).isFalse();

        JsonNode items = queue.get("items");
        assertThat(items.size()).isEqualTo(3);
        // groups contiguous: B's answer, then A's two (attempt-age order)
        assertThat(items.get(0).get("answer").get("answerId").asText())
                .isEqualTo(bAnswer.toString());
        assertThat(items.get(1).get("paperId").asText())
                .isEqualTo(paperA.paperId().toString());
        assertThat(items.get(2).get("paperId").asText())
                .isEqualTo(paperA.paperId().toString());

        // 2. determinism: a second read returns the same order
        JsonNode again = get("/api/v1/teacher/marking/queue-v2?state=PENDING", teacher);
        assertThat(again.get("items").get(0).get("answer").get("answerId").asText())
                .isEqualTo(items.get(0).get("answer").get("answerId").asText());
        assertThat(again.get("items").get(2).get("answer").get("answerId").asText())
                .isEqualTo(items.get(2).get("answer").get("answerId").asText());

        // 3. mark→next chain: follows the ordered items, last points nowhere
        assertThat(items.get(0).get("nextAnswerId").asText())
                .isEqualTo(items.get(1).get("answer").get("answerId").asText());
        assertThat(items.get(1).get("nextAnswerId").asText())
                .isEqualTo(items.get(2).get("answer").get("answerId").asText());
        assertThat(items.get(2).get("nextAnswerId").isNull()).isTrue();

        // 4. human-mark the oldest (the mark&next workflow's POST), with the
        //    per-point decision the κ pairing needs — marker = the real teacher
        Map<String, Integer> decisions = new LinkedHashMap<>();
        decisions.put(paperB.scheme().points().get(0).id().toString(), 1);
        var principal = teacher();
        markingService.recordHumanMark(bAnswer, principal.user().id(),
                2, decisions, "it-mark");

        // after marking, the queue no longer contains it (mark→next advances)
        JsonNode afterMark = get("/api/v1/teacher/marking/queue-v2?state=PENDING", teacher);
        assertThat(afterMark.get("items").size()).isEqualTo(2);
        assertThat(afterMark.get("groups").size()).isEqualTo(1);   // only paper A left

        // 5. bounded batch over real answers: blank (deterministic MARKED),
        //    unknown (FAILED), already human-marked (SKIPPED) — honest mix
        JsonNode batch = post("/api/v1/teacher/marking/smart-mark-batch", teacher,
                "{\"answerIds\":[\"" + aAnswer1 + "\",\"" + UUID.randomUUID() + "\",\""
                        + bAnswer + "\"]}");
        assertThat(batch.get("requested").asInt()).isEqualTo(3);
        assertThat(batch.get("marked").asInt()).isEqualTo(1);
        assertThat(batch.get("failed").asInt()).isEqualTo(1);
        assertThat(batch.get("skipped").asInt()).isEqualTo(1);
        JsonNode outcomes = batch.get("items");
        assertThat(outcomes.get(0).get("outcome").asText()).isEqualTo("MARKED");
        assertThat(outcomes.get(0).get("marksAwarded").asInt()).isZero();   // blank answer
        assertThat(outcomes.get(1).get("outcome").asText()).isEqualTo("FAILED");
        assertThat(outcomes.get(2).get("outcome").asText()).isEqualTo("SKIPPED_ALREADY_MARKED");

        // 6. throughput counts what actually happened
        JsonNode throughput = get("/api/v1/teacher/marking/throughput", teacher);
        JsonNode byState = throughput.get("answersByState");
        assertThat(byState.get("HUMAN_MARKED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(byState.get("SMART_MARKED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(byState.get("PENDING").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(throughput.get("humanMarks24h").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(throughput.get("oldestPendingAt").isNull()).isFalse();
        assertThat(throughput.get("oldestPendingHours").isNull()).isFalse();
        // pending-by-paper leaders: paper A only (paper B fully marked)
        JsonNode leaders = throughput.get("pendingByPaper");
        assertThat(leaders.size()).isEqualTo(1);
        assertThat(leaders.get(0).get("paperId").asText())
                .isEqualTo(paperA.paperId().toString());
    }

    // ── the §7 review-queue v3 over real ingested content ───────────────

    @Test
    @DisplayName("review-queue-v3 carries §7 signals + rank reasons, deterministically")
    void reviewQueueV3Signals() throws Exception {
        String teacher = teacherToken();

        // one SUGGESTED paper (ingested, unvalidated) with a scheme-linked question
        var summary = ingestion.ingest(draft("4MQ0/3C"), UUID.randomUUID());

        JsonNode view = get("/api/v1/teacher/content/review-queue-v3", teacher);

        assertThat(view.get("suggestedVersions").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode ours = null;
        for (JsonNode p : view.get("papers")) {
            if (p.get("id").asText().equals(summary.paperId().toString())) {
                ours = p;
            }
        }
        assertThat(ours).isNotNull();
        assertThat(ours.get("totalQuestions").asLong()).isEqualTo(1);
        assertThat(ours.get("questionsWithScheme").asLong()).isEqualTo(1);
        assertThat(ours.get("validationState").asText()).isEqualTo("SUGGESTED");
        JsonNode reasons = ours.get("rankReasons");
        assertThat(reasons.size()).isGreaterThanOrEqualTo(1);
        assertThat(reasons.toString()).contains("mark scheme linked");
        // practicable set is exposed so the reviewer sees the baseline
        assertThat(view.has("practicableTopicCount")).isTrue();

        // determinism: the same order on a second read
        JsonNode again = get("/api/v1/teacher/content/review-queue-v3", teacher);
        assertThat(again.get("papers").size()).isEqualTo(view.get("papers").size());
        for (int i = 0; i < view.get("papers").size(); i++) {
            assertThat(again.get("papers").get(i).get("id").asText())
                    .isEqualTo(view.get("papers").get(i).get("id").asText());
        }
    }

}
