package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
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
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.research.TelemetryEventRepository;
import com.syllabai.smartmark.SmartMarkAgreementEvaluation;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkService;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Integration test: the full Wave-1/2 content and marking loop against a real
 * Postgres (Master Spec §33; ADR-005 same pgvector image family as Neon). Runs in
 * CI where Docker exists; skipped locally otherwise.
 *
 * <p>Covers: draft ingestion (all SUGGESTED) → teacher validation → structured
 * submission (timed) → honest provider-unavailable smart-mark failure → human
 * mark fires evidence exactly once (BKT + fluency-gap react) → deterministic
 * blank-answer smart mark (provisional pre-gate) → κ gate evaluation (passes on
 * paired per-point decisions) → post-gate smart mark is authoritative and fires
 * evidence without a human → telemetry marking events.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class MultipartMarkingFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private com.syllabai.assessment.AssessmentService assessment;
    @Autowired
    private SmartMarkService smartMarkService;
    @Autowired
    private TeacherMarkingService teacherMarkingService;
    @Autowired
    private AuthService authService;
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
    @Autowired
    private AttemptRepository attempts;
    @Autowired
    private SkillStateRepository skillStates;
    @Autowired
    private TelemetryEventRepository telemetry;

    private static PastPaperDraftDto draft() {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2013-" + UUID.randomUUID().toString().substring(0, 6),
                        "4CH0/2C", "it-qp-doc", "it-ms-doc"),
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

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-learner-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("ingest → validate → submit → mark → κ gate → authoritative marking → telemetry")
    void fullContentAndMarkingLoop() {
        UUID learner = newLearner();
        UUID marker = UUID.randomUUID();

        // 1. ingest: paper, question, part, mark point — all SUGGESTED
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft(), UUID.randomUUID());
        assertThat(summary.questions()).isEqualTo(1);
        assertThat(summary.parts()).isEqualTo(1);
        assertThat(summary.markPoints()).isEqualTo(1);
        assertThat(examPapers.findById(summary.paperId()).orElseThrow().validationState())
                .isEqualTo(ExamPaper.ValidationState.SUGGESTED);

        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        assertThat(version.validationState())
                .isEqualTo(QuestionVersion.ValidationState.SUGGESTED);

        // 2. teacher validation: version, then scheme with authored criteria
        review.validateQuestionVersion(version.id());
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
        assertThat(markSchemes.findWithPoints(scheme.id()).orElseThrow().validationState())
                .isEqualTo(MarkScheme.ValidationState.VALIDATED);

        // 3. structured submission (timed) with real answer content
        UUID partId = version.parts().get(0).id();
        StructuredAttemptResultView timed = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "the correct content")),
                        30000L, 4, false, true));
        assertThat(timed.markingState()).isEqualTo("PENDING");
        Attempt timedAttempt = attempts.findById(timed.attemptId()).orElseThrow();
        assertThat(timedAttempt.evidenceEmitted()).isFalse();

        // 4. smart mark without LLM keys fails honestly — never fabricates marks
        Answer timedAnswer = answers.findByAttemptIdOrderByQuestionPartId(
                timedAttempt.id()).get(0);
        SmartMarkResult failed = smartMarkService.markAnswer(timedAnswer.id());
        assertThat(failed.validationPassed()).isFalse();
        assertThat(failed.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(timedAnswer.markingState()).isEqualTo(Answer.MarkingState.PENDING);

        // 5. human mark: authoritative, fires evidence exactly once, BKT reacts
        Map<String, Integer> full = new LinkedHashMap<>();
        full.put(scheme.points().get(0).id().toString(), 1);
        teacherMarkingService.recordHumanMark(timedAnswer.id(), marker, 2, full, "earned");
        assertThat(timedAttempt.evidenceEmitted()).isTrue();
        assertThat(timedAttempt.marksAwarded()).isEqualTo(2);
        assertThat(timedAnswer.markingState()).isEqualTo(Answer.MarkingState.HUMAN_MARKED);
        SkillState state = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId()).orElseThrow();
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.proceduralFluencyGap()).isNull();   // untimed not observed yet

        // 6. blank answer (untimed): deterministic smart mark, provisional pre-gate
        StructuredAttemptResultView untimed = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "")),
                        20000L, 2, true, false));
        Answer blankAnswer = answers.findByAttemptIdOrderByQuestionPartId(
                untimed.attemptId()).get(0);
        SmartMarkResult blankRun = smartMarkService.markAnswer(blankAnswer.id());
        assertThat(blankRun.validationPassed()).isTrue();
        assertThat(blankRun.marksAwarded()).isZero();
        assertThat(blankAnswer.markingState()).isEqualTo(Answer.MarkingState.SMART_MARKED);
        Attempt untimedAttempt = attempts.findById(untimed.attemptId()).orElseThrow();
        assertThat(untimedAttempt.evidenceEmitted()).isFalse();   // gate closed

        // 7. human mark on the blank answer + κ evaluation (gate passes, κ = 1)
        Map<String, Integer> empty = new LinkedHashMap<>();
        empty.put(scheme.points().get(0).id().toString(), 0);
        teacherMarkingService.recordHumanMark(blankAnswer.id(), marker, 0, empty, "blank");
        SmartMarkAgreementEvaluation evaluation =
                teacherMarkingService.evaluateAgreement(null, marker);
        assertThat(evaluation.sampleSize()).isGreaterThanOrEqualTo(1);
        assertThat(evaluation.kappa()).isEqualTo(1.0);
        assertThat(evaluation.passed()).isTrue();

        // fluency gap now paired: timed acc 1.0, untimed acc 0.0 → gap -1.0
        SkillState paired = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId()).orElseThrow();
        assertThat(paired.proceduralFluencyGap()).isEqualTo(-1.0);

        // 8. gate passed: a fresh smart mark is authoritative — evidence without human
        StructuredAttemptResultView released = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "")),
                        15000L, 3, false, true));
        Answer releasedAnswer = answers.findByAttemptIdOrderByQuestionPartId(
                released.attemptId()).get(0);
        SmartMarkResult authoritative = smartMarkService.markAnswer(releasedAnswer.id());
        assertThat(authoritative.validationPassed()).isTrue();
        assertThat(releasedAnswer.markingState()).isEqualTo(Answer.MarkingState.SMART_MARKED);
        assertThat(attempts.findById(released.attemptId()).orElseThrow()
                .evidenceEmitted()).isTrue();

        // 9. the marking loop is fully in the research record
        assertThat(telemetry.count()).isGreaterThanOrEqualTo(3);
        assertThat(telemetry.findAll().stream()
                .filter(e -> e.type() == com.syllabai.research.TelemetryEvent.Type.ATTEMPT_SUBMITTED)
                .count()).isEqualTo(3);
        assertThat(telemetry.findAll().stream()
                .filter(e -> e.type() == com.syllabai.research.TelemetryEvent.Type.SMART_MARK_COMPLETED)
                .count()).isEqualTo(3);
        assertThat(telemetry.findAll().stream()
                .filter(e -> e.type() == com.syllabai.research.TelemetryEvent.Type.HUMAN_MARK_RECORDED)
                .count()).isEqualTo(2);
        assertThat(telemetry.findAll().stream()
                .filter(e -> e.type() == com.syllabai.research.TelemetryEvent.Type.SELF_DOUBT_FLAGGED)
                .count()).isEqualTo(1);
    }
}
