package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredAttemptResultView;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.learner.SmartLessonService;
import com.syllabai.learner.dto.SmartLessonView;
import com.syllabai.learner.dto.SmartLessonView.ActionType;
import com.syllabai.learner.dto.SmartLessonView.ReasonCode;
import com.syllabai.shared.events.TutorAnsweredEvent;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Integration test: the sprint-2 §8 CLOSED LOOP and the §12 marking → learner
 * state path, against real Postgres with the V6/V7 seed and real evidence
 * events — not fixture swaps:
 *
 * <pre>
 * initial learner state
 *        ↓
 * Smart Lesson recommendation A
 *        ↓
 * new learner evidence (attempt / misconception-tagged wrong answer / doubt signal / human mark)
 *        ↓
 * recompute
 *        ↓
 * recommendation B  — and B changes for the CORRECT reason (the evidence trace says so)
 * </pre>
 *
 * Also pins: the §8 repeated-exposure rotation (the starter question moves
 * past attempted questions), the §9 doubt-signal advance redirect through the
 * REAL event → recorder → engagement-row pipeline, and the honest PENDING
 * contract of structured answers (a pending answer never becomes learner
 * state; the first authoritative human mark fires the evidence).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class SmartLessonFlowIT {

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
    /** V6 seed: WCH11-T1.1 (mole calculations) — carries SEED MCQs 001/002. */
    private static final UUID TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V6 seed: WCH11-T1.2 (empirical formulae) — carries SEED-WCH11-003. */
    private static final UUID TOPIC_T1_2 =
            UUID.fromString("20000000-0000-0000-0000-000000000013");
    /** V6 seed: WCH11-T2.1 — carries SEED-WCH11-004/005. */
    private static final UUID TOPIC_T2_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000022");
    /** V7 seed: SEED-WCH11-001. */
    private static final UUID SEED_MCQ_1 =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: SEED-WCH11-002. */
    private static final UUID SEED_MCQ_2 =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    /** V7 seed: option A of 001 — wrong, tagged with the mole misconception. */
    private static final UUID SEED_MCQ_1_WRONG_TAGGED =
            UUID.fromString("41000000-0000-0000-0000-000000000001");
    /** V7 seed: option C of 001 — correct. */
    private static final UUID SEED_MCQ_1_CORRECT =
            UUID.fromString("41000000-0000-0000-0000-000000000003");
    /** V7 seed: option B of 001 — wrong, NO misconception tag. */
    private static final UUID SEED_MCQ_1_WRONG_PLAIN =
            UUID.fromString("41000000-0000-0000-0000-000000000002");
    /** V7 seed: option A of 002 — the correct answer for SEED-WCH11-002. */
    private static final UUID SEED_MCQ_2_CORRECT =
            UUID.fromString("41000000-0000-0000-0000-000000000011");
    /** V7 seed: option B of 002 — wrong, NO misconception tag. */
    private static final UUID SEED_MCQ_2_WRONG_PLAIN =
            UUID.fromString("41000000-0000-0000-0000-000000000012");

    /** V7 seed: SEED-WCH11-003 (empirical formula) — secondary-mapped to T1.1. */
    private static final UUID SEED_MCQ_3 =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    /** V7 seed: SEED-WCH11-005 (chlorine ion) — secondary-mapped to T1.1. */
    private static final UUID SEED_MCQ_5 =
            UUID.fromString("40000000-0000-0000-0000-000000000005");

    @Autowired
    private SmartLessonService smartLesson;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherMarkingService teacherMarkingService;
    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private QuestionTopicRepository questionTopics;
    @Autowired
    private ApplicationEventPublisher events;

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-sl-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "Smart Lesson It")).user().id();
    }

    private void submitMcq(UUID learner, UUID questionId, UUID optionId) {
        assessment.submit(learner, new SubmitAnswerRequest(
                questionId, optionId, 25_000L, 4, false, false));
    }

    @Test
    @DisplayName("closed loop: cold start → misconception evidence changes the action for the right reason")
    void misconceptionEvidenceChangesTheAction() {
        UUID learner = newLearner();

        // A: cold state — diagnostic practice with a starter question
        SmartLessonView before = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(before.policy()).isEqualTo("smart-lesson/v2");
        assertThat(before.topicStatus().coverage()).isEqualTo("UNMEASURED");
        assertThat(before.action().reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);
        assertThat(before.action().questionId()).isNotNull();

        // new evidence: one misconception-tagged wrong answer (BDT 0.75 active)
        submitMcq(learner, SEED_MCQ_1, SEED_MCQ_1_WRONG_TAGGED);

        // B: recompute — the action changed BECAUSE of the misconception evidence
        SmartLessonView after = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(after.action().reasonCode())
                .isIn(ReasonCode.MISCONCEPTION_SUSPECTED, ReasonCode.MISCONCEPTION_REMEDIATION);
        assertThat(after.topicStatus().strongestMisconceptionProbability()).isGreaterThanOrEqualTo(0.5);
        assertThat(after.topicStatus().coverage()).isNotEqualTo("UNMEASURED");
        assertThat(after.evidence()).extracting("key").anyMatch(k -> String.valueOf(k).startsWith("misconception"));
        // the reason names the evidence that caused it
        assertThat(after.action().reasonDetail()).contains("probability");
    }

    @Test
    @DisplayName("closed loop: mastery → advance, then a REAL doubt signal redirects the advance target")
    void doubtSignalRedirectsAdvance() {
        UUID learner = newLearner();

        // A: two correct answers establish mastery on WCH11-T1.1
        submitMcq(learner, SEED_MCQ_1, SEED_MCQ_1_CORRECT);
        submitMcq(learner, SEED_MCQ_2, SEED_MCQ_2_CORRECT);

        SmartLessonView mastered = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(mastered.action().actionType()).isEqualTo(ActionType.ADVANCE_TOPIC);
        assertThat(mastered.action().targetNodeId()).isNotEqualTo(TOPIC_T1_1);
        // the standing rule: first prerequisite-ready unstarted topic in curriculum order
        assertThat(mastered.action().targetNodeId()).isEqualTo(TOPIC_T1_2);
        assertThat(mastered.action().reasonDetail()).contains("mastered");

        // new evidence: the learner reports confusion about WCH11-T2.1 through the
        // REAL event pipeline (classifier → recorder → engagement row)
        events.publishEvent(new TutorAnsweredEvent(
                learner, "I don't understand ionic bonding", List.of(TOPIC_T2_1),
                4, List.of("KNOWLEDGE_NODE"), false, "openai/gpt-oss-120b",
                "tutor-grounded/v1", 900.0, Instant.now(), "EXPLANATION"));

        // B: recompute — the advance target changed for the correct reason:
        // their own doubt signal jumps the queue
        SmartLessonView redirected = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(redirected.action().actionType()).isEqualTo(ActionType.ADVANCE_TOPIC);
        assertThat(redirected.action().targetNodeId()).isEqualTo(TOPIC_T2_1);
        assertThat(redirected.action().reasonDetail()).contains("confusion");
        assertThat(redirected.evidence()).extracting("key").contains("confusion signal");
        // the doubt is evidence of engagement, never mastery
        assertThat(redirected.topicStatus().coverage()).isNotEqualTo("UNMEASURED");   // T1.1 itself is measured
    }

    @Test
    @DisplayName("closed loop: repeated-exposure avoidance rotates the starter question after attempts")
    void starterQuestionRotatesAfterAttempts() {
        UUID learner = newLearner();

        // A: cold state — diagnostic practice names the deterministic starter.
        // WCH11-T1.1 serves FOUR seed questions (001/002 primary, 003/005
        // secondary-mapped through question_topics).
        SmartLessonView cold = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(cold.action().reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);
        UUID starter = cold.action().questionId();
        assertThat(starter).isIn(SEED_MCQ_1, SEED_MCQ_2, SEED_MCQ_3, SEED_MCQ_5);
        assertThat(cold.action().reasonDetail()).doesNotContain("revisiting");

        // attempt the STARTER question twice (plain wrong options — no misconception
        // tag, so the misconception rung stays out of the way)
        UUID starterWrongOption = starter.equals(SEED_MCQ_1)
                ? SEED_MCQ_1_WRONG_PLAIN : SEED_MCQ_2_WRONG_PLAIN;
        submitMcq(learner, starter, starterWrongOption);
        submitMcq(learner, starter, starterWrongOption);

        // B: weak mastery now established (2 attempts) — and the starter moves
        // past the attempted question
        SmartLessonView second = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(second.action().reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(second.topicStatus().attempts()).isEqualTo(2);
        UUID next = second.action().questionId();
        assertThat(next).isNotEqualTo(starter);   // the rotation is real
        assertThat(next).isIn(SEED_MCQ_1, SEED_MCQ_2, SEED_MCQ_3, SEED_MCQ_5);
        assertThat(second.action().reasonDetail())
                .contains("not attempted yet").contains("1 of 4");
    }

    @Test
    @DisplayName("§12: a PENDING structured answer never becomes learner state; the human mark fires the loop")
    void pendingMarkingIsHonestUntilTheHumanMarkFiresEvidence() {
        UUID learner = newLearner();
        UUID marker = UUID.randomUUID();

        // one validated structured question, mapped (secondary) to WCH11-T1.1
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft(), UUID.randomUUID());
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
        questionTopics.save(new QuestionTopic(question, TOPIC_T1_1, false));

        // structured submission → PENDING, no evidence
        UUID partId = version.parts().get(0).id();
        StructuredAttemptResultView submitted = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "a wrong guess")),
                        30000L, 3, false, true));
        assertThat(submitted.markingState()).isEqualTo("PENDING");

        // A: the pending answer is INVISIBLE to the learner model — honest gap
        SmartLessonView before = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(before.topicStatus().attempts()).isZero();
        assertThat(before.topicStatus().coverage()).isEqualTo("UNMEASURED");
        assertThat(before.action().reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);

        // the first authoritative human mark fires the evidence (0 of 2 marks)
        UUID answerId = answers.findByAttemptIdOrderByQuestionPartId(
                submitted.attemptId()).get(0).id();
        Map<String, Integer> zero = new LinkedHashMap<>();
        zero.put(scheme.points().get(0).id().toString(), 0);
        teacherMarkingService.recordHumanMark(answerId, marker, 0, zero, "not earned");

        // B: the marking evidence IS in the learner model now — the action
        // changed for the correct reason (skill state exists, one attempt)
        SmartLessonView after = smartLesson.lessonFor(learner, SUBJECT_ROOT, TOPIC_T1_1);
        assertThat(after.topicStatus().attempts()).isEqualTo(1);
        assertThat(after.topicStatus().coverage()).isEqualTo("PARTIAL");
        assertThat(after.action().reasonCode()).isNotEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);
        assertThat(after.evidence()).extracting("key").contains("attempts");
    }

    @Test
    @DisplayName("HTTP parity: the smart-lesson endpoint is authenticated and serves the v2 policy")
    void smartLessonEndpointParity() throws Exception {
        UUID learner = newLearner();

        // anonymous → 401
        HttpResponse<String> anon = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port
                                + "/api/v1/learners/me/smart-lesson?rootId=" + SUBJECT_ROOT
                                + "&topicNodeId=" + TOPIC_T1_1))
                .header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).isEqualTo(401);

        // learner token → 200, v2 policy, closed loop over HTTP
        String email = "it-sl-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        String token = authService.register(new RegisterRequest(
                email, "ItLearner123!", "Smart Lesson Http")).accessToken();
        HttpResponse<String> ok = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port
                                + "/api/v1/learners/me/smart-lesson?rootId=" + SUBJECT_ROOT
                                + "&topicNodeId=" + TOPIC_T1_1))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(ok.body());
        assertThat(body.get("policy").asText()).isEqualTo("smart-lesson/v2");
        assertThat(body.get("action").get("reasonCode").asText())
                .isEqualTo("INSUFFICIENT_COVERAGE");
    }

    private static PastPaperDraftDto draft() {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2013-" + UUID.randomUUID().toString().substring(0, 6),
                        "SL-" + UUID.randomUUID().toString().substring(0, 6),
                        "it-qp-doc", "it-ms-doc"),
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
}
