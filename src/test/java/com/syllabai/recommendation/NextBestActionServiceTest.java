package com.syllabai.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The nba-rules/v1 deterministic baseline (T-033, ADR-017): every rule tier must
 * produce evidence-backed actions with structured reason codes, respect the
 * subject-subtree hard constraint, keep one action per topic, cap the response,
 * and be fully deterministic. No reason string is invented — each carries the
 * measured values that produced it.
 */
class NextBestActionServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID TOPIC_A = UUID.randomUUID();
    private static final UUID TOPIC_B = UUID.randomUUID();
    private static final UUID TOPIC_C = UUID.randomUUID();
    private static final UUID MIS_M1 = UUID.randomUUID();
    private static final UUID OUTSIDE = UUID.randomUUID();   // different subject's node
    private static final Instant NOW = Instant.now();
    private static final Instant PRACTISED = NOW.minusSeconds(3600);

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final ReviewScheduleRepository reviewSchedules = mock(ReviewScheduleRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);

    private final NextBestActionService service = new NextBestActionService(
            graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
            new LearnerProperties(null, null, null, null),
            new RecommendationProperties(0, 0, 0, 0, 0, 0, 0),
            answers, servableQuestions);

    // ── fixtures ───────────────────────────────────────────────────

    /** SUBJECT root → UNIT → TOPIC_A/B/C; TOPIC_A carries misconception MIS_M1. */
    private NodeView tree() {
        NodeView mis = node(MIS_M1, "M-A1", "MISCONCEPTION", "Confuses moles with mass");
        NodeView a = node(TOPIC_A, "U1-T1", "TOPIC", "Mole Calculations", List.of(mis));
        NodeView b = node(TOPIC_B, "U1-T2", "TOPIC", "Reacting Masses");
        NodeView c = node(TOPIC_C, "U1-T3", "TOPIC", "Titration Calculations");
        NodeView unit = node(UNIT, "U1", "UNIT", "Unit 1", List.of(a, b, c));
        return node(ROOT, "IAL-CHEM", "SUBJECT", "IAL Chemistry", List.of(unit));
    }

    private static NodeView node(UUID id, String code, String type, String title) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null, List.of());
    }

    private static NodeView node(UUID id, String code, String type, String title,
                                 List<NodeView> children) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null, children);
    }

    private void givenTree() {
        when(graph.treeWithMisconceptions(ROOT)).thenReturn(tree());
        when(graph.prerequisiteRelations(ROOT)).thenReturn(List.of(
                new PrerequisiteRelation(TOPIC_A, TOPIC_B)));   // B depends on A
    }

    private void givenNoEvidence() {
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
    }

    private SkillState skill(UUID nodeId, int attempts, double mastery) {
        SkillState s = new SkillState(LEARNER, nodeId, mastery, PRACTISED);
        for (int i = 0; i < attempts; i++) {
            s.recordAttempt(false, mastery, PRACTISED);
        }
        return s;
    }

    private ReviewSchedule review(UUID nodeId, Instant dueAt) {
        return new ReviewSchedule(LEARNER, nodeId, dueAt,
                ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, 0.55);
    }

    private MisconceptionState misconception(UUID nodeId, double probability) {
        MisconceptionState m = new MisconceptionState(LEARNER, nodeId, 0.4, PRACTISED);
        m.update(probability, PRACTISED);
        return m;
    }

    /** a graded answer of {@code awarded}/{@code max} marks on a question whose primary topic is {@code topicId} */
    private Answer gradedAnswer(UUID questionId, UUID topicId, int awarded, int max) {
        Question question = new Question("EXT-" + questionId, Question.Type.STRUCTURED,
                "stem", max, 2, 300, "explain", topicId, Question.Provenance.SEED_DEMO);
        setId(question, questionId);
        QuestionVersion version = new QuestionVersion(question, 1, "stem", max, 2, 300,
                "explain", QuestionVersion.ValidationState.VALIDATED, null, null, null);
        QuestionPart part = new QuestionPart(version, "a", "prompt", "explain", max, 0);
        var attempt = new com.syllabai.assessment.Attempt(
                LEARNER, question, null, false, null, 0, null, false, false, "test");
        Answer answer = new Answer(attempt, part, "answer text");
        answer.humanMarked(awarded);
        return answer;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test fixture cannot set id", e);
        }
    }

    private static void setFluencyGap(SkillState s, double gap) {
        try {
            Field f = SkillState.class.getDeclaredField("proceduralFluencyGap");
            f.setAccessible(true);
            f.set(s, gap);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── T1 due reviews ─────────────────────────────────────────────

    @Test
    @DisplayName("due reviews become REVIEW_TOPIC actions, overdue first, subtree-scoped only")
    void dueReviewsOverdueFirstAndSubjectScoped() {
        givenTree();
        givenNoEvidence();
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(LEARNER,
                ReviewSchedule.Status.PENDING)).thenReturn(List.of(
                review(TOPIC_B, NOW.plusSeconds(86400)),      // due tomorrow
                review(OUTSIDE, NOW.minusSeconds(86400)),     // different subject — excluded
                review(TOPIC_A, NOW.minusSeconds(86400))));   // overdue since yesterday

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(2);
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.REVIEW_TOPIC);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.DUE_REVIEW);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(TOPIC_A);   // overdue first
        assertThat(view.actions().get(0).reasonDetail()).contains("overdue");
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(TOPIC_B);
        assertThat(view.actions().stream().map(a -> a.targetNodeId()))
                .doesNotContain(OUTSIDE);
        assertThat(view.policy()).isEqualTo("nba-rules/v1");
        assertThat(view.actions().get(0).rank()).isEqualTo(1);
        assertThat(view.actions().get(1).rank()).isEqualTo(2);
    }

    // ── T2 prerequisites ───────────────────────────────────────────

    @Test
    @DisplayName("weak prerequisite of an established-weak dependent → REVIEW_PREREQUISITE on the prerequisite; the dependent keeps its own practice action at lower rank")
    void prerequisiteRemediation() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 2, 0.30),    // weak prerequisite
                skill(TOPIC_B, 3, 0.20)));  // established-weak dependent

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(2);
        var action = view.actions().get(0);
        assertThat(action.actionType()).isEqualTo(ActionType.REVIEW_PREREQUISITE);
        assertThat(action.reasonCode()).isEqualTo(ReasonCode.PREREQUISITE_WEAK);
        assertThat(action.targetNodeId()).isEqualTo(TOPIC_A);
        assertThat(action.reasonDetail()).contains("U1-T2");   // names the dependent topic
        assertThat(action.reasonDetail()).contains("0.30");
        // the dependent topic itself stays practisable — remediation outranks, it does not replace
        assertThat(view.actions().get(1).actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(view.actions().get(1).reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(TOPIC_B);
    }

    @Test
    @DisplayName("strong prerequisite ⇒ no remediation; the weak dependent itself gets LOW_MASTERY practice")
    void strongPrerequisiteFallsThroughToWeakMastery() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 5, 0.90),    // strong prerequisite
                skill(TOPIC_B, 3, 0.20)));  // weak dependent

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(1);
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(TOPIC_B);
    }

    // ── T3 problem questions ───────────────────────────────────────

    @Test
    @DisplayName("low-mark graded answer → RETRY_PROBLEM_QUESTION, only when the question is still servable")
    void problemQuestionRetryServableOnly() {
        givenTree();
        givenNoEvidence();
        UUID q1 = UUID.randomUUID();   // servable, primary topic A
        UUID q2 = UUID.randomUUID();   // unservable (e.g. content un-validated since)
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of(
                gradedAnswer(q2, TOPIC_C, 0, 4),    // most recent but unservable
                gradedAnswer(q1, TOPIC_A, 1, 4)));  // 1/4 marks, servable
        when(servableQuestions.isServable(q1)).thenReturn(true);
        when(servableQuestions.isServable(q2)).thenReturn(false);
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(3);

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        // the retry is rank 1; uncovered topics B/C follow (validated questions exist there)
        assertThat(view.actions()).hasSize(3);
        var action = view.actions().get(0);
        assertThat(action.actionType()).isEqualTo(ActionType.RETRY_PROBLEM_QUESTION);
        assertThat(action.reasonCode()).isEqualTo(ReasonCode.PROBLEM_QUESTION);
        assertThat(action.questionId()).isEqualTo(q1);
        assertThat(action.targetNodeId()).isEqualTo(TOPIC_A);
        assertThat(action.reasonDetail()).contains("1/4");
        // the unservable question's answer produced no action
        assertThat(view.actions()).noneMatch(a -> q2.equals(a.questionId()));
    }

    @Test
    @DisplayName("unmarked or comfortably-passed answers never become problem actions")
    void pendingAndHighMarkAnswersExcluded() {
        givenTree();
        givenNoEvidence();
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        Answer graded = gradedAnswer(q1, TOPIC_A, 1, 4);
        Answer unmarked = new Answer(graded.attempt(), graded.questionPart(), "x");  // PENDING
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of(
                unmarked, gradedAnswer(q2, TOPIC_B, 4, 4)));
        when(servableQuestions.isServable(q1)).thenReturn(true);
        when(servableQuestions.isServable(q2)).thenReturn(true);

        assertThat(service.actionsFor(LEARNER, ROOT).actions()).isEmpty();
    }

    // ── T4 misconceptions ──────────────────────────────────────────

    @Test
    @DisplayName("active BDT misconception → ASK_TUTOR on the misconception node, parent topic named")
    void activeMisconceptionAsksTutor() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of(
                misconception(MIS_M1, 0.75)));   // ≥ bdt activeThreshold (0.5)

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(1);
        var action = view.actions().get(0);
        assertThat(action.actionType()).isEqualTo(ActionType.ASK_TUTOR);
        assertThat(action.reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_SUSPECTED);
        assertThat(action.targetNodeId()).isEqualTo(MIS_M1);
        assertThat(action.reasonDetail()).contains("0.75");
        assertThat(action.reasonDetail()).contains("U1-T1");       // parent topic
        assertThat(action.reasonDetail()).contains("grounded explanation");
    }

    @Test
    @DisplayName("below-threshold misconceptions stay invisible")
    void inactiveMisconceptionExcluded() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of(
                misconception(MIS_M1, 0.20)));

        assertThat(service.actionsFor(LEARNER, ROOT).actions()).isEmpty();
    }

    // ── T5 fluency gaps ────────────────────────────────────────────

    @Test
    @DisplayName("measured fluency gap ≥ threshold → TIMED_EXERCISE; small gaps stay invisible")
    void fluencyGapBecomesTimedExercise() {
        givenTree();
        givenNoEvidence();
        SkillState gapped = skill(TOPIC_C, 5, 0.80);
        setFluencyGap(gapped, 0.30);
        SkillState smallGap = skill(TOPIC_B, 5, 0.80);
        setFluencyGap(smallGap, 0.10);
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(gapped, smallGap));

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(1);
        var action = view.actions().get(0);
        assertThat(action.actionType()).isEqualTo(ActionType.TIMED_EXERCISE);
        assertThat(action.reasonCode()).isEqualTo(ReasonCode.FLUENCY_GAP);
        assertThat(action.targetNodeId()).isEqualTo(TOPIC_C);
        assertThat(action.reasonDetail()).contains("0.30").contains("timed");
    }

    // ── T6 weak mastery ────────────────────────────────────────────

    @Test
    @DisplayName("LOW_MASTERY needs the evidence floor (min attempts) and ranks weakest first")
    void weakMasteryEvidenceFloorAndOrdering() {
        when(graph.treeWithMisconceptions(ROOT)).thenReturn(tree());
        when(graph.prerequisiteRelations(ROOT)).thenReturn(List.of());   // isolate T6
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_B, 3, 0.35),
                skill(TOPIC_C, 2, 0.10),
                skill(TOPIC_A, 1, 0.05)));   // 1 attempt — below the floor, excluded

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(2);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(TOPIC_C);   // weakest first
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(view.actions().get(0).reasonDetail()).contains("band");
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(TOPIC_B);
    }

    // ── T7 uncovered topics (constrained exploration) ──────────────

    @Test
    @DisplayName("uncovered topics surface only with servable questions, capped at 2, curriculum order")
    void uncoveredTopicsConstrainedExploration() {
        givenTree();
        givenNoEvidence();
        when(servableQuestions.countServableByTopic(TOPIC_A)).thenReturn(2);
        when(servableQuestions.countServableByTopic(TOPIC_B)).thenReturn(0);  // nothing to practise
        when(servableQuestions.countServableByTopic(TOPIC_C)).thenReturn(5);

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(2);   // A and C — B has no validated questions
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(TOPIC_A);   // curriculum order
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.UNCOVERED_TOPIC);
        assertThat(view.actions().get(0).servableQuestionCount()).isEqualTo(2);
        assertThat(view.actions().get(0).reasonDetail()).contains("No attempt evidence yet");
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(TOPIC_C);
        assertThat(view.actions().get(1).servableQuestionCount()).isEqualTo(5);
    }

    // ── portfolio discipline ───────────────────────────────────────

    @Test
    @DisplayName("one action per topic: a due review outranks the same topic's weak mastery")
    void oneActionPerTopicReviewWins() {
        givenTree();
        givenNoEvidence();
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(LEARNER,
                ReviewSchedule.Status.PENDING)).thenReturn(List.of(review(TOPIC_A, NOW)));
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 3, 0.20)));

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(1);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.DUE_REVIEW);
    }

    @Test
    @DisplayName("maxActions caps the response even with many weak topics")
    void maxActionsCap() {
        List<SkillState> weak = new ArrayList<>();
        List<NodeView> topics = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID id = UUID.randomUUID();
            weak.add(skill(id, 3, 0.10 + i * 0.01));
            topics.add(node(id, "T-" + i, "TOPIC", "Topic " + i));
        }
        NodeView unit = node(UNIT, "U1", "UNIT", "Unit 1", topics);
        NodeView root = node(ROOT, "IAL-CHEM", "SUBJECT", "IAL Chemistry", List.of(unit));
        when(graph.treeWithMisconceptions(ROOT)).thenReturn(root);
        when(graph.prerequisiteRelations(ROOT)).thenReturn(List.of());
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(weak);

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).hasSize(8);   // properties.maxActions default
        assertThat(view.actions().get(7).rank()).isEqualTo(8);
    }

    // ── determinism & empty state ─────────────────────────────────

    @Test
    @DisplayName("deterministic: identical evidence produces identical ranked actions")
    void deterministic() {
        givenTree();
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(LEARNER,
                ReviewSchedule.Status.PENDING)).thenReturn(List.of(review(TOPIC_A, NOW)));
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_B, 3, 0.20), skill(TOPIC_C, 4, 0.75)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of(
                misconception(MIS_M1, 0.75)));
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(1);

        NextBestActionsView first = service.actionsFor(LEARNER, ROOT);
        NextBestActionsView second = service.actionsFor(LEARNER, ROOT);

        assertThat(first.actions()).containsExactlyElementsOf(second.actions());
    }

    @Test
    @DisplayName("no evidence and no servable questions ⇒ an honest empty action list")
    void emptyState() {
        givenTree();
        givenNoEvidence();
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(0);

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).isEmpty();
        assertThat(view.learnerId()).isEqualTo(LEARNER);
        assertThat(view.rootId()).isEqualTo(ROOT);
        assertThat(view.asOf()).isNotNull();
    }
}
