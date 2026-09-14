package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.dto.SmartLessonView;
import com.syllabai.learner.dto.SmartLessonView.ActionType;
import com.syllabai.learner.dto.SmartLessonView.ReasonCode;
import com.syllabai.recommendation.ConceptDependencyGraph;
import com.syllabai.recommendation.ConceptDependencyGraph.RawEdge;
import com.syllabai.recommendation.RecommendationProperties;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smart Lesson MVP decision ladder: every branch is deterministic over the
 * same evidence maps the NBA engine consumes, so each rule is pinned by a
 * focused fixture. The closed-loop property (new evidence changes the next
 * action) is pinned by the mastered→advance and weak→practise pair.
 */
class SmartLessonServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID TOPIC_A = UUID.randomUUID();   // "U1-T1" Mole Calculations
    private static final UUID TOPIC_B = UUID.randomUUID();   // "U1-T2" Reacting Masses (depends on A)
    private static final UUID TOPIC_C = UUID.randomUUID();   // "U1-T3" Titration
    private static final UUID MIS_M1 = UUID.randomUUID();    // misconception on TOPIC_A
    private static final Instant NOW = Instant.now();
    private static final Instant PRACTISED = NOW.minusSeconds(3600);

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final ReviewScheduleRepository reviewSchedules = mock(ReviewScheduleRepository.class);
    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);
    private final TutorTopicEngagementRepository engagements =
            mock(TutorTopicEngagementRepository.class);

    private final SmartLessonService service = new SmartLessonService(
            graph, learnerModel, reviewSchedules, ConceptDependencyGraph.empty(),
            servableQuestions, engagements,
            new LearnerProperties(null, null, null, null),
            new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
            new com.syllabai.learner.decay.EbbinghausDecayService());

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
        // TOPIC_B depends on TOPIC_A (dependent -> prerequisite)
        when(graph.prerequisiteRelations(ROOT)).thenReturn(List.of(
                new PrerequisiteRelation(TOPIC_A, TOPIC_B)));
    }

    private void givenNoEvidence() {
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        when(engagements.findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                any(UUID.class), any(Instant.class))).thenReturn(List.of());
    }

    private SkillState skill(UUID nodeId, int attempts, double mastery) {
        SkillState s = new SkillState(LEARNER, nodeId, mastery, PRACTISED);
        for (int i = 0; i < attempts; i++) {
            s.recordAttempt(false, mastery, PRACTISED);
        }
        return s;
    }

    private MisconceptionState misconception(UUID nodeId, double probability) {
        MisconceptionState m = new MisconceptionState(LEARNER, nodeId, 0.2, PRACTISED);
        m.update(probability, PRACTISED);
        return m;
    }

    private void givenServable(UUID topicId, int count) {
        List<StudentQuestionView> qs =
                java.util.stream.IntStream.range(0, count)
                        .mapToObj(i -> new StudentQuestionView(
                                UUID.randomUUID(), "EXT-" + i, "STRUCTURED", "stem", 3,
                                1 + i, 120, "State", topicId, null,
                                List.of(), List.of()))
                        .toList();
        when(servableQuestions.activeByTopic(topicId)).thenReturn(qs);
        // the advance walk precomputes counts with one batched activeWithin
        // query — stub it with the same questions so both surfaces agree
        when(servableQuestions.activeWithin(any())).thenReturn(qs);
    }

    // ── the ladder ─────────────────────────────────────────────────

    @Test
    @DisplayName("unmeasured topic with no other signals -> START (insufficient coverage) with the starter question")
    void unmeasuredTopicStarts() {
        givenTree();
        givenNoEvidence();
        givenServable(TOPIC_A, 3);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_A);
        assertThat(lesson.action().questionId()).isNotNull();
        assertThat(lesson.action().servableQuestionCount()).isEqualTo(3);
        assertThat(lesson.topicStatus().coverage()).isEqualTo("UNMEASURED");
        assertThat(lesson.evidence()).extracting("key").contains("attempts");
    }

    @Test
    @DisplayName("a measured-weak direct prerequisite redirects the lesson to the prerequisite")
    void weakPrerequisiteRedirects() {
        givenTree();
        givenNoEvidence();
        // TOPIC_B selected; its prerequisite TOPIC_A measured weak
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 3, 0.25)));

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_B);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.REMEDIATE_PREREQUISITE);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.PREREQUISITE_WEAK);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_A);
        assertThat(lesson.action().reasonDetail()).contains("U1-T1").contains("foundation");

        // KG learner surface (§4): the prerequisite panel carries the
        // measured overlay for the same prerequisite
        assertThat(lesson.prerequisites()).hasSize(1);
        assertThat(lesson.prerequisites().get(0).code()).isEqualTo("U1-T1");
        assertThat(lesson.prerequisites().get(0).measuredWeak()).isTrue();
        assertThat(lesson.prerequisites().get(0).effectiveMastery()).isNotNull();
    }

    @Test
    @DisplayName("a strong prerequisite does NOT block the lesson (evidence gates the graph)")
    void strongPrerequisiteDoesNotBlock() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 5, 0.9)));
        givenServable(TOPIC_B, 2);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_B);

        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_COVERAGE);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_B);
    }

    @Test
    @DisplayName("active misconception with a validated REMEDIATED_BY edge -> study the corrective concept")
    void misconceptionWithCorrectiveEdgeStudiesIt() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 4, 0.7)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of(
                misconception(MIS_M1, 0.8)));
        ConceptDependencyGraph withEdge = ConceptDependencyGraph.of(List.of(
                new RawEdge("M-A1", "REMEDIATED_BY", "U1-T3", "HUMAN_VALIDATED")),
                Set.of("M-A1", "U1-T3"));
        SmartLessonService withGraph = new SmartLessonService(graph, learnerModel,
                reviewSchedules, withEdge, servableQuestions, engagements,
                new LearnerProperties(null, null, null, null),
                new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
                new com.syllabai.learner.decay.EbbinghausDecayService());

        SmartLessonView lesson = withGraph.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.STUDY_CORRECTIVE);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_REMEDIATION);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_C);   // the corrective target
    }

    @Test
    @DisplayName("active misconception with no corrective edge -> ask the Tutor (grounded explanation)")
    void misconceptionWithoutEdgeAsksTutor() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 4, 0.7)));
        when(learnerModel.misconceptionStates(LEARNER)).thenReturn(List.of(
                misconception(MIS_M1, 0.8)));

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.ASK_TUTOR);
        assertThat(lesson.action().targetNodeId()).isEqualTo(MIS_M1);
        assertThat(lesson.topicStatus().strongestMisconceptionProbability()).isEqualTo(0.8);

        // misconception panel: the attached misconception with its probability
        // overlay and no remediation edge (empty graph here)
        assertThat(lesson.misconceptions()).hasSize(1);
        assertThat(lesson.misconceptions().get(0).title()).isEqualTo("Confuses moles with mass");
        assertThat(lesson.misconceptions().get(0).probability()).isEqualTo(0.8);
        assertThat(lesson.misconceptions().get(0).active()).isTrue();
        assertThat(lesson.misconceptions().get(0).remediationNodeCode()).isNull();
    }

    @Test
    @DisplayName("established weak mastery -> practise with the LOW_MASTERY reason")
    void establishedWeakMasteryPractises() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 4, 0.3)));
        givenServable(TOPIC_A, 5);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
        assertThat(lesson.action().servableQuestionCount()).isEqualTo(5);
        assertThat(lesson.topicStatus().coverage()).isEqualTo("ESTABLISHED");
    }

    @Test
    @DisplayName("tutor-engaged but never practised -> PRACTISE with the tutor-engagement reason")
    void tutorEngagedWithoutPractice() {
        givenTree();
        givenNoEvidence();
        when(engagements.findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                any(UUID.class), any(Instant.class))).thenReturn(List.of(
                new TutorTopicEngagement(LEARNER, TOPIC_A, NOW.minusSeconds(600),
                        3, false, "groq/openai/gpt-oss-120b")));
        givenServable(TOPIC_A, 4);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.TUTOR_ENGAGED);
        assertThat(lesson.topicStatus().tutorAsks()).isEqualTo(1);
    }

    @Test
    @DisplayName("mastered topic -> ADVANCE to the next unstarted topic in curriculum order")
    void masteredTopicAdvances() {
        givenTree();
        givenNoEvidence();
        // TOPIC_A mastered (0.9, 5 attempts); TOPIC_B unstarted comes before TOPIC_C
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 5, 0.9)));
        givenServable(TOPIC_B, 2);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.ADVANCE_TOPIC);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.TOPIC_MASTERED);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_B);
        assertThat(lesson.action().targetCode()).isEqualTo("U1-T2");
    }

    @Test
    @DisplayName("all topics strong -> honest consolidation, no invented work")
    void allStrongConsolidates() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(
                skill(TOPIC_A, 5, 0.9), skill(TOPIC_B, 5, 0.85), skill(TOPIC_C, 5, 0.95)));

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.REVIEW_TOPIC);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.TOPIC_MASTERED);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_A);   // consolidate here
        assertThat(lesson.action().reasonDetail()).contains("every curriculum topic");
    }

    @Test
    @DisplayName("a topic outside the subject subtree is a 404 (hard subject isolation)")
    void topicOutsideSubjectIs404() {
        givenTree();
        givenNoEvidence();

        assertThatThrownBy(() -> service.lessonFor(LEARNER, ROOT, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("review schedule pending on the topic -> REVIEW_TOPIC once mastered-level mastery holds")
    void dueReviewWinsAfterPrereqGate() {
        givenTree();
        givenNoEvidence();
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 5, 0.7)));
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of());
        ReviewSchedule schedule = mock(ReviewSchedule.class);
        when(schedule.nodeId()).thenReturn(TOPIC_A);
        when(schedule.dueAt()).thenReturn(NOW.minusSeconds(3600));
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                LEARNER, ReviewSchedule.Status.PENDING)).thenReturn(List.of(schedule));

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.REVIEW_TOPIC);
        assertThat(lesson.action().reasonCode()).isEqualTo(ReasonCode.DUE_REVIEW);
        assertThat(lesson.topicStatus().reviewDue()).isTrue();
    }

    @Test
    @DisplayName("advance prefers an unstarted topic the learner reported confusion about (V23 wiring)")
    void advancePrefersConfusionSignalledTopic() {
        givenTree();
        givenNoEvidence();
        // TOPIC_A mastered; the learner reported confusion on TOPIC_C (later in
        // curriculum order than the unstarted TOPIC_B)
        when(learnerModel.skillStates(LEARNER)).thenReturn(List.of(skill(TOPIC_A, 5, 0.9)));
        when(engagements.findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                any(UUID.class), any(Instant.class))).thenReturn(List.of(
                new TutorTopicEngagement(LEARNER, TOPIC_C, NOW.minusSeconds(600),
                        3, false, "openai/gpt-oss-120b", "DOUBT_SIGNAL")));
        givenServable(TOPIC_B, 2);
        givenServable(TOPIC_C, 2);

        SmartLessonView lesson = service.lessonFor(LEARNER, ROOT, TOPIC_A);

        assertThat(lesson.action().actionType()).isEqualTo(ActionType.ADVANCE_TOPIC);
        assertThat(lesson.action().targetNodeId()).isEqualTo(TOPIC_C);   // doubt jumps the queue
        assertThat(lesson.action().reasonDetail()).contains("confusion");
    }
}
