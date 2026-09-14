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
import com.syllabai.recommendation.ConceptDependencyGraph;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *
 * <p>All pre-existing tests construct the service with
 * {@link ConceptDependencyGraph#empty()}: the v1.1 graph-aware stages (T2b/T4b)
 * must be inert without applicable validated relationships, so the v1 baseline
 * below is also the Case-C no-graph-evidence regression proof. The graph-aware
 * behaviour itself is covered by the T2b/T4b test group at the bottom.</p>
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
    private final NextBestActionService.TutorEngagementViewReader engagementReader =
            mock(NextBestActionService.TutorEngagementViewReader.class);

    private final NextBestActionService service = new NextBestActionService(
            graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
            new LearnerProperties(null, null, null, null),
            new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
            answers, servableQuestions, ConceptDependencyGraph.empty(), engagementReader);

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
        when(engagementReader.askCountsSince(Mockito.any(UUID.class), Mockito.any(Instant.class)))
                .thenReturn(Map.of());
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
        assertThat(view.policy()).isEqualTo("nba-rules/v1.2");
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

    // ── T7 uncovered topics (constrained exploration) ──────────

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

    // ── T2b/T4b — graph-aware stages (v1.1, settled T-C11 concept graph) ──
    //
    // Fixtures use REAL edges of the settled store (Batch-4 close, 2026-09-13):
    //   Case A: 4CH1-CON-BOND-ENERGY-CALC -REQUIRES_PREREQUISITE-> 4CH1-CON-COVALENT-BOND (HUMAN_VALIDATED)
    //   Case B: 4CH1-MIS-BOND-ENERGY-COUNT -REMEDIATED_BY-> 4CH1-CON-BOND-ENERGY-CALC     (HUMAN_VALIDATED)
    //   Case D frozen edges (must stay excluded):
    //     4CH1-CON-REACTING-MASS -REQUIRES_PREREQUISITE-> 4CH1-CON-EQ-SYMBOL              (SUGGESTED pilot HOLD)
    //     4CH1-MIS-EQ-SUBSCRIPT -REMEDIATED_BY-> 4CH1-CON-CONSERVATION-MASS               (SUGGESTED pilot HOLD)
    //     4CH1-CON-CRYSTALLISATION -REQUIRES_PREREQUISITE-> 4CH1-CON-SOLUTION             (REVIEW_REQUIRED)

    private static final String CODE_BEC = "4CH1-CON-BOND-ENERGY-CALC";
    private static final String CODE_COV = "4CH1-CON-COVALENT-BOND";
    private static final String CODE_MIS_BEC = "4CH1-MIS-BOND-ENERGY-COUNT";
    private static final String CODE_RM = "4CH1-CON-REACTING-MASS";
    private static final String CODE_EQS = "4CH1-CON-EQ-SYMBOL";
    private static final String CODE_MIS_EQS = "4CH1-MIS-EQ-SUBSCRIPT";
    private static final String CODE_CONM = "4CH1-CON-CONSERVATION-MASS";
    private static final String CODE_CRY = "4CH1-CON-CRYSTALLISATION";
    private static final String CODE_SOL = "4CH1-CON-SOLUTION";

    private static final UUID LEARNER2 = UUID.randomUUID();
    private static final UUID G_ROOT = UUID.randomUUID();
    private static final UUID G_UNIT = UUID.randomUUID();
    private static final UUID G_BEC = UUID.randomUUID();
    private static final UUID G_COV = UUID.randomUUID();
    private static final UUID G_MIS_BEC = UUID.randomUUID();
    private static final UUID G_RM = UUID.randomUUID();
    private static final UUID G_EQS = UUID.randomUUID();
    private static final UUID G_MIS_EQS = UUID.randomUUID();
    private static final UUID G_CONM = UUID.randomUUID();
    private static final UUID G_CRY = UUID.randomUUID();
    private static final UUID G_SOL = UUID.randomUUID();

    private static java.util.Set<String> codes(String... more) {
        java.util.Set<String> all = new java.util.HashSet<>(java.util.List.of(
                CODE_BEC, CODE_COV, CODE_MIS_BEC, CODE_RM, CODE_EQS,
                CODE_MIS_EQS, CODE_CONM, CODE_CRY, CODE_SOL));
        all.addAll(java.util.Arrays.asList(more));
        return java.util.Set.copyOf(all);
    }

    private static ConceptDependencyGraph.RawEdge edge(String source, String relation,
                                                       String target, String status) {
        return new ConceptDependencyGraph.RawEdge(source, relation, target, status);
    }

    /** the two validated edges of the settled slice + their frozen non-validated counterparts (Case-D companions) */
    private static ConceptDependencyGraph settledSliceGraph() {
        return ConceptDependencyGraph.of(List.of(
                edge(CODE_BEC, "REQUIRES_PREREQUISITE", CODE_COV, "HUMAN_VALIDATED"),
                edge(CODE_MIS_BEC, "REMEDIATED_BY", CODE_BEC, "HUMAN_VALIDATED"),
                // frozen non-validated counterparts of the same shapes — must never act
                edge(CODE_RM, "REQUIRES_PREREQUISITE", CODE_EQS, "SUGGESTED"),        // pilot HOLD
                edge(CODE_MIS_EQS, "REMEDIATED_BY", CODE_CONM, "SUGGESTED"),          // pilot HOLD
                edge(CODE_CRY, "REQUIRES_PREREQUISITE", CODE_SOL, "REVIEW_REQUIRED")),
                codes());
    }

    /** SUBJECT → UNIT → CON-BOND-ENERGY-CALC (with MIS-BOND-ENERGY-COUNT folded in) + CON-COVALENT-BOND */
    private NodeView settledSliceTree() {
        NodeView mis = node(G_MIS_BEC, CODE_MIS_BEC, "MISCONCEPTION",
                "Counts every bond occurrence as one bond in bond-energy sums");
        NodeView bec = node(G_BEC, CODE_BEC, "TOPIC",
                "Bond energy calculations", List.of(mis));
        NodeView cov = node(G_COV, CODE_COV, "TOPIC", "Covalent bond");
        NodeView unit = node(G_UNIT, "4CH1-S3", "UNIT", "Section 3 Physical", List.of(bec, cov));
        return node(G_ROOT, "4CH1", "SUBJECT", "Edexcel IGCSE Chemistry", List.of(unit));
    }

    private void givenSettledSliceNoEvidence() {
        when(graph.treeWithMisconceptions(G_ROOT)).thenReturn(settledSliceTree());
        when(graph.prerequisiteRelations(G_ROOT)).thenReturn(List.of());
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
    }

    @Test
    @DisplayName("Case A: established-weak dependent + validated prerequisite chain → REVIEW_PREREQUISITE on the unmeasured prerequisite, honestly worded; dependent keeps its own action")
    void validatedPrerequisiteChainNominatesRemediation() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(G_BEC, 3, 0.20)));
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(2);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).hasSize(2);
        var action = view.actions().get(0);
        assertThat(action.actionType()).isEqualTo(ActionType.REVIEW_PREREQUISITE);
        assertThat(action.reasonCode()).isEqualTo(ReasonCode.VALIDATED_PREREQUISITE_CHAIN);
        assertThat(action.targetNodeId()).isEqualTo(G_COV);
        assertThat(action.targetCode()).isEqualTo(CODE_COV);
        assertThat(action.reasonDetail()).contains(CODE_BEC);              // names the dependent
        assertThat(action.reasonDetail()).contains("0.20");               // measured weakness
        assertThat(action.reasonDetail()).contains("not yet measured");   // honest about the prerequisite
        // remediation outranks downstream practice — it does not replace it
        assertThat(view.actions().get(1).actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(view.actions().get(1).reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(G_BEC);
    }

    @Test
    @DisplayName("Case A evidence gate: a prerequisite measured strong overrides the graph's nomination — no remediation, dependent practices")
    void measuredStrongPrerequisiteOverridesGraph() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(G_BEC, 3, 0.20),
                skill(G_COV, 4, 0.90)));
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(2);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).noneMatch(a -> a.actionType() == ActionType.REVIEW_PREREQUISITE);
        assertThat(view.actions()).singleElement()
                .satisfies(a -> {
                    assertThat(a.reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
                    assertThat(a.targetNodeId()).isEqualTo(G_BEC);
                });
    }

    @Test
    @DisplayName("graph edges are not learner state: the full graph with zero learner evidence produces zero graph actions")
    void graphWithoutLearnerEvidenceNeverActs() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();

        assertThat(graphService.actionsFor(LEARNER, G_ROOT).actions()).isEmpty();
    }

    @Test
    @DisplayName("Case B: active misconception + validated REMEDIATED_BY → corrective action on the concept; the ASK_TUTOR action is preserved")
    void validatedRemediationSurfacesCorrectiveAction() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(
                List.of(misconception(G_MIS_BEC, 0.75)));

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).hasSize(2);
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.ASK_TUTOR);   // existing T4, unchanged
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(G_MIS_BEC);
        var corrective = view.actions().get(1);
        assertThat(corrective.actionType()).isEqualTo(ActionType.REMEDIATE_MISCONCEPTION);
        assertThat(corrective.reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_REMEDIATION);
        assertThat(corrective.targetNodeId()).isEqualTo(G_BEC);                        // the corrective concept
        assertThat(corrective.targetCode()).isEqualTo(CODE_BEC);
        assertThat(corrective.reasonDetail()).contains(CODE_MIS_BEC);                  // traces the misconception
        assertThat(corrective.reasonDetail()).contains("0.75");                        // measured probability
        assertThat(corrective.reasonDetail()).contains("validated remediation");
    }

    @Test
    @DisplayName("Case B threshold: a below-threshold misconception produces no remediation action at all")
    void belowThresholdMisconceptionNoRemediation() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(
                List.of(misconception(G_MIS_BEC, 0.20)));

        assertThat(graphService.actionsFor(LEARNER, G_ROOT).actions()).isEmpty();
    }

    @Test
    @DisplayName("Case D: frozen pilot HOLD (SUGGESTED) and REVIEW_REQUIRED edges never affect authoritative output — only the validated counterparts act")
    void frozenEdgesExcludedFromAuthoritativeOutput() {
        ConceptDependencyGraph mixed = ConceptDependencyGraph.of(List.of(
                edge(CODE_BEC, "REQUIRES_PREREQUISITE", CODE_COV, "HUMAN_VALIDATED"),
                edge(CODE_RM, "REQUIRES_PREREQUISITE", CODE_EQS, "SUGGESTED"),        // frozen pilot HOLD
                edge(CODE_MIS_EQS, "REMEDIATED_BY", CODE_CONM, "SUGGESTED"),          // frozen pilot HOLD
                edge(CODE_CRY, "REQUIRES_PREREQUISITE", CODE_SOL, "REVIEW_REQUIRED")),
                codes());
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, mixed, engagementReader);
        // the tree carries every code the frozen edges touch, so any leakage would surface
        NodeView misEqs = node(G_MIS_EQS, CODE_MIS_EQS, "MISCONCEPTION", "Subscripts in equations");
        NodeView eqs = node(G_EQS, CODE_EQS, "TOPIC", "State symbols", List.of(misEqs));
        NodeView rm = node(G_RM, CODE_RM, "TOPIC", "Reacting mass calculations");
        NodeView conm = node(G_CONM, CODE_CONM, "TOPIC", "Conservation of mass");
        NodeView cry = node(G_CRY, CODE_CRY, "TOPIC", "Crystallisation");
        NodeView sol = node(G_SOL, CODE_SOL, "TOPIC", "Solution preparation");
        NodeView bec = node(G_BEC, CODE_BEC, "TOPIC", "Bond energy calculations");
        NodeView cov = node(G_COV, CODE_COV, "TOPIC", "Covalent bond");
        NodeView unit = node(G_UNIT, "4CH1", "UNIT", "Edexcel IGCSE Chemistry",
                List.of(bec, cov, eqs, rm, conm, cry, sol));
        when(graph.treeWithMisconceptions(G_ROOT)).thenReturn(
                node(G_ROOT, "4CH1-ROOT", "SUBJECT", "Chemistry", List.of(unit)));
        when(graph.prerequisiteRelations(G_ROOT)).thenReturn(List.of());
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(G_BEC, 3, 0.20),     // would trigger the validated BEC→COV edge
                skill(G_RM, 3, 0.15),      // would trigger the SUGGESTED RM→EQS edge if it leaked
                skill(G_CRY, 3, 0.10)));   // would trigger the RR CRY→SOL edge if it leaked
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(
                List.of(misconception(G_MIS_EQS, 0.75)));   // would trigger the SUGGESTED RB edge if it leaked
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(0);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        // exactly one graph-derived action: the validated chain
        assertThat(view.actions().stream()
                .filter(a -> a.reasonCode() == ReasonCode.VALIDATED_PREREQUISITE_CHAIN))
                .singleElement()
                .satisfies(a -> assertThat(a.targetNodeId()).isEqualTo(G_COV));
        // none of the frozen edges' targets may receive any action
        assertThat(view.actions().stream().map(NextBestActionsView.NextBestActionView::targetNodeId))
                .doesNotContain(G_EQS, G_CONM, G_SOL);
        assertThat(view.actions())
                .noneMatch(a -> a.actionType() == ActionType.REMEDIATE_MISCONCEPTION);
    }

    @Test
    @DisplayName("Case C: a populated graph whose codes match nothing in the KG subtree leaves the baseline output unchanged")
    void graphWithNoMatchingCodesLeavesBaselineUnchanged() {
        NextBestActionService withGraph = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenTree();   // the IAL-coded tree — no T-C11 code matches
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 2, 0.30), skill(TOPIC_B, 3, 0.20)));

        assertThat(withGraph.actionsFor(LEARNER, ROOT).actions())
                .containsExactlyElementsOf(service.actionsFor(LEARNER, ROOT).actions());
    }

    @Test
    @DisplayName("subject isolation: a graph candidate whose prerequisite sits outside the requested root's subtree is invisible")
    void graphEndpointsOutsideSubtreeIgnored() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        // the tree carries BEC but NOT CON-COVALENT-BOND (it lives under another root)
        NodeView bec = node(G_BEC, CODE_BEC, "TOPIC", "Bond energy calculations");
        NodeView unit = node(G_UNIT, "4CH1-S3", "UNIT", "Section 3 Physical", List.of(bec));
        when(graph.treeWithMisconceptions(G_ROOT)).thenReturn(
                node(G_ROOT, "4CH1", "SUBJECT", "Edexcel IGCSE Chemistry", List.of(unit)));
        when(graph.prerequisiteRelations(G_ROOT)).thenReturn(List.of());
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(G_BEC, 3, 0.20)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(2);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).noneMatch(a -> a.actionType() == ActionType.REVIEW_PREREQUISITE);
        assertThat(view.actions()).singleElement()
                .satisfies(a -> {
                    assertThat(a.reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
                    assertThat(a.targetNodeId()).isEqualTo(G_BEC);
                });
    }

    @Test
    @DisplayName("learner isolation: same graph, different evidence — only the learner with weakness gets the graph-informed action")
    void learnerIsolationSameGraphDifferentEvidence() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        when(graph.treeWithMisconceptions(G_ROOT)).thenReturn(settledSliceTree());
        when(graph.prerequisiteRelations(G_ROOT)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                Mockito.any(UUID.class), Mockito.eq(ReviewSchedule.Status.PENDING)))
                .thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(Mockito.any(UUID.class)))
                .thenReturn(List.of());
        // countServableByTopic deliberately left unstubbed (returns 0): no validated
        // questions ⇒ no T7 uncovered-topic noise — the isolation claim is about
        // the graph stage alone
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(G_BEC, 3, 0.20)));
        when(learnerModel.skillStates(LEARNER2)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(LEARNER2)).thenReturn(List.of());

        NextBestActionsView learner1 = graphService.actionsFor(LEARNER, G_ROOT);
        NextBestActionsView learner2 = graphService.actionsFor(LEARNER2, G_ROOT);

        assertThat(learner1.actions().stream()
                .map(NextBestActionsView.NextBestActionView::reasonCode))
                .contains(ReasonCode.VALIDATED_PREREQUISITE_CHAIN);
        assertThat(learner2.actions()).isEmpty();   // same graph, zero evidence ⇒ zero actions
    }

    @Test
    @DisplayName("deterministic with a populated graph: identical evidence ⇒ identical ranked actions")
    void deterministicWithPopulatedGraph() {
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settledSliceGraph(), engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(G_BEC, 3, 0.20)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(
                List.of(misconception(G_MIS_BEC, 0.75)));
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(2);

        NextBestActionsView first = graphService.actionsFor(LEARNER, G_ROOT);
        NextBestActionsView second = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(first.actions()).containsExactlyElementsOf(second.actions());
    }

    @Test
    @DisplayName("maxActions still caps graph candidates: ten validated chains surface at most 8 actions")
    void maxActionsCapsGraphCandidates() {
        List<ConceptDependencyGraph.RawEdge> raw = new ArrayList<>();
        java.util.Set<String> allCodes = new java.util.HashSet<>();
        List<NodeView> dependents = new ArrayList<>();
        List<SkillState> weak = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String depCode = "4CH1-CON-DEP-" + i;
            String preCode = "4CH1-CON-PRE-" + i;
            raw.add(edge(depCode, "REQUIRES_PREREQUISITE", preCode, "HUMAN_VALIDATED"));
            allCodes.add(depCode);
            allCodes.add(preCode);
            UUID depId = UUID.randomUUID();
            UUID preId = UUID.randomUUID();
            dependents.add(node(depId, depCode, "TOPIC", "Dependent " + i));
            dependents.add(node(preId, preCode, "TOPIC", "Prerequisite " + i));
            weak.add(skill(depId, 3, 0.10 + i * 0.01));
        }
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, ConceptDependencyGraph.of(raw, allCodes), engagementReader);
        NodeView unit = node(G_UNIT, "4CH1", "UNIT", "Unit", dependents);
        when(graph.treeWithMisconceptions(G_ROOT)).thenReturn(
                node(G_ROOT, "4CH1-ROOT", "SUBJECT", "Chemistry", List.of(unit)));
        when(graph.prerequisiteRelations(G_ROOT)).thenReturn(List.of());
        when(learnerModel.skillStates(LEARNER)).thenReturn(weak);
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(answers.findByLearnerIdOrderByCreatedAtDesc(LEARNER)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(1);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).hasSize(8);   // properties.maxActions default
        assertThat(view.actions()).allMatch(a -> a.actionType() == ActionType.REVIEW_PREREQUISITE);
        assertThat(view.actions().get(7).rank()).isEqualTo(8);
    }

    @Test
    @DisplayName("the REAL settled store (packaged snapshot) drives both graph-aware stages from real learner evidence")
    void realSettledStoreDrivesRecommendations() {
        // 153 HUMAN_VALIDATED semantic edges of the closed Batch-4 store, loaded
        // through the same SHA-256-pinned loader the Spring context uses
        ConceptDependencyGraph settled = new ConceptDependencyGraphLoader().load();
        assertThat(settled.validatedEdgeCount()).isEqualTo(153);
        NextBestActionService graphService = new NextBestActionService(
                graph, learnerModel, reviewSchedules, new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                answers, servableQuestions, settled, engagementReader);
        givenSettledSliceNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(G_BEC, 3, 0.20)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(
                List.of(misconception(G_MIS_BEC, 0.75)));
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(2);

        NextBestActionsView view = graphService.actionsFor(LEARNER, G_ROOT);

        assertThat(view.actions()).hasSize(3);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.VALIDATED_PREREQUISITE_CHAIN);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(G_COV);
        assertThat(view.actions().get(1).actionType()).isEqualTo(ActionType.ASK_TUTOR);
        assertThat(view.actions().get(2).actionType()).isEqualTo(ActionType.REMEDIATE_MISCONCEPTION);
        assertThat(view.actions().get(2).targetNodeId()).isEqualTo(G_BEC);
        // the wrong-answer-pattern edge of the same misconception is loaded but NOT
        // consumed by the NBA (only REQUIRES_PREREQUISITE and REMEDIATED_BY are)
        assertThat(view.actions()).noneMatch(a -> a.targetCode().equals(CODE_MIS_BEC)
                && a.actionType() == ActionType.REMEDIATE_MISCONCEPTION);
    }

    // ── T7a tutor engagement (V21, P7) ──────────────────────────

    @Test
    @DisplayName("a recently-asked unpractised topic becomes TUTOR_ENGAGED ahead of uncovered topics")
    void tutorEngagedTopicOutranksUncovered() {
        givenTree();
        givenNoEvidence();
        when(servableQuestions.countServableByTopic(TOPIC_A)).thenReturn(2);
        when(servableQuestions.countServableByTopic(TOPIC_B)).thenReturn(3);
        when(servableQuestions.countServableByTopic(TOPIC_C)).thenReturn(4);
        when(engagementReader.askCountsSince(Mockito.any(UUID.class), Mockito.any(Instant.class)))
                .thenReturn(Map.of(TOPIC_C, 3L));   // learner asked about C thrice

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        // C leads with TUTOR_ENGAGED; A/B follow as plain uncovered (curriculum order)
        assertThat(view.actions()).hasSize(3);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(TOPIC_C);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.TUTOR_ENGAGED);
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(view.actions().get(0).servableQuestionCount()).isEqualTo(4);
        assertThat(view.actions().get(0).reasonDetail()).contains("3 time(s)");
        assertThat(view.actions().get(1).reasonCode()).isEqualTo(ReasonCode.UNCOVERED_TOPIC);
        assertThat(view.policy()).isEqualTo("nba-rules/v1.2");
    }

    @Test
    @DisplayName("topics with attempt evidence never produce TUTOR_ENGAGED (no double-counting)")
    void tutorEngagementSkipsPractisedTopics() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_C, 3, 0.5)));
        when(servableQuestions.countServableByTopic(TOPIC_C)).thenReturn(4);
        when(engagementReader.askCountsSince(Mockito.any(UUID.class), Mockito.any(Instant.class)))
                .thenReturn(Map.of(TOPIC_C, 3L));   // asked AND practised

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        // C is practised (mastery 0.5 above weak ceiling) — its asks must NOT
        // resurrect it as TUTOR_ENGAGED; evidence tiers own practised topics
        assertThat(view.actions()).noneMatch(a -> a.reasonCode() == ReasonCode.TUTOR_ENGAGED);
    }

    @Test
    @DisplayName("asks about nodes outside the subject subtree are ignored (subject isolation)")
    void tutorEngagementIsSubjectScoped() {
        givenTree();
        givenNoEvidence();
        when(servableQuestions.countServableByTopic(TOPIC_A)).thenReturn(2);
        when(engagementReader.askCountsSince(Mockito.any(UUID.class), Mockito.any(Instant.class)))
                .thenReturn(Map.of(OUTSIDE, 5L));   // another subject's topic

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).noneMatch(a -> a.reasonCode() == ReasonCode.TUTOR_ENGAGED);
        assertThat(view.actions()).allMatch(a -> !a.targetNodeId().equals(OUTSIDE));
    }

    @Test
    @DisplayName("asked topics with no servable questions produce no action (boundary holds)")
    void tutorEngagementRequiresServableContent() {
        givenTree();
        givenNoEvidence();
        when(servableQuestions.countServableByTopic(TOPIC_C)).thenReturn(0);  // nothing validated
        when(engagementReader.askCountsSince(Mockito.any(UUID.class), Mockito.any(Instant.class)))
                .thenReturn(Map.of(TOPIC_C, 2L));

        NextBestActionsView view = service.actionsFor(LEARNER, ROOT);

        assertThat(view.actions()).noneMatch(a -> a.targetNodeId().equals(TOPIC_C));
    }
}
