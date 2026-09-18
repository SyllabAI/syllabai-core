package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.recommendation.RecommendationProperties;
import com.syllabai.teacher.ClassAnalyticsService.ClassOverviewView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * P9 smallest-useful Test Builder: the assembly must reuse the ONE serving
 * boundary (only servable questions enter a generated test), dedupe across
 * selected topics, cap and difficulty-order the result, honour subject
 * isolation, and attach the answer key from the current mark scheme.
 */
class TestBuilderServiceTest {

    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final ClassAnalyticsService classAnalytics = mock(ClassAnalyticsService.class);
    private final QuestionTopicRepository questionTopics = mock(QuestionTopicRepository.class);
    private final TestBuilderService service = new TestBuilderService(
            servableQuestions, knowledgeGraph, questionVersions, markSchemes,
            classAnalytics, new RecommendationProperties(0, 0, 0, 0, 0, 0, 0, 0),
            questionTopics);

    private final UUID root = UUID.randomUUID();
    private final UUID topicA = UUID.randomUUID();
    private final UUID topicB = UUID.randomUUID();
    private final UUID outside = UUID.randomUUID();

    private KnowledgeNode topicNode(UUID id, String code) {
        KnowledgeNode n = mock(KnowledgeNode.class);
        when(n.id()).thenReturn(id);
        when(n.code()).thenReturn(code);
        when(n.title()).thenReturn("title " + code);
        return n;
    }

    private StudentQuestionView question(UUID id, int difficulty, int marks) {
        return new StudentQuestionView(id, "ref-" + id, "STRUCTURED", "stem " + id, marks,
                difficulty, 300, null, null, null, List.of(),
                List.of(new StudentQuestionView.PartView(
                        UUID.randomUUID(), "a", "prompt", null, marks)), List.of());
    }

    /** a servable view whose PRIMARY topic is the given node (targeting counts) */
    private StudentQuestionView questionOn(UUID id, UUID topicId) {
        return new StudentQuestionView(id, "ref-" + id, "MCQ_SINGLE", "stem " + id, 1,
                1, 60, null, topicId, null, List.of(), List.of(), List.of());
    }

    /** a secondary topic-mapping row for the targeting counts */
    private QuestionTopic mapping(UUID questionId, UUID topicId) {
        Question q = mock(Question.class);
        when(q.id()).thenReturn(questionId);
        return new QuestionTopic(q, topicId, false);
    }

    private void givenSubject() {
        when(knowledgeGraph.subtreeIds(root)).thenReturn(List.of(root, topicA, topicB));
        KnowledgeNode nodeA = topicNode(topicA, "4CH1-S2-b");   // built BEFORE the when() chain
        KnowledgeNode nodeB = topicNode(topicB, "4CH1-S4-b");   // (never mock inside thenReturn args)
        when(knowledgeGraph.node(topicA)).thenReturn(nodeA);
        when(knowledgeGraph.node(topicB)).thenReturn(nodeB);
    }

    @Test
    @DisplayName("assembles servable questions from the selected topics, deduped, difficulty-ordered, capped")
    void assemblesFromBoundary() {
        givenSubject();
        UUID shared = UUID.randomUUID();      // mapped under BOTH topics — must appear once
        UUID easy = UUID.randomUUID();
        UUID hard = UUID.randomUUID();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                question(hard, 5, 9), question(shared, 3, 6)));
        when(servableQuestions.activeByTopic(topicB)).thenReturn(List.of(
                question(shared, 3, 6), question(easy, 1, 4)));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(2);
        when(servableQuestions.countServableByTopic(topicB)).thenReturn(2);

        var view = service.preview(root, List.of(topicA, topicB), 2, null, false);

        assertThat(view.questionCount()).isEqualTo(2);          // cap applied
        assertThat(view.totalMarks()).isEqualTo(10);            // easy(4) + shared(6)
        assertThat(view.questions()).extracting(q -> q.id())
                .containsExactly(easy, shared);                 // difficulty order, dedup
        assertThat(view.questions()).allMatch(q -> q.topicCode() != null);
        assertThat(view.questions()).allMatch(q -> q.answers().isEmpty());  // no answers requested
        assertThat(view.topics()).hasSize(2);
        assertThat(view.topics()).allMatch(t -> t.servableQuestions() == 2);
    }

    @Test
    @DisplayName("topics outside the subject subtree are ignored (subject isolation)")
    void subjectIsolation() {
        givenSubject();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(question(UUID.randomUUID(), 2, 5)));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(1);

        var view = service.preview(root, List.of(topicA, outside), 20, null, false);

        assertThat(view.topics()).extracting(t -> t.topicNodeId()).containsExactly(topicA);
        assertThat(view.questionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("includeAnswers attaches the current scheme's points with its validation state")
    void answerKeyFromScheme() {
        givenSubject();
        UUID qid = UUID.randomUUID();
        StudentQuestionView q = question(qid, 2, 6);
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(q));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(1);

        QuestionVersion version = mock(QuestionVersion.class);
        when(version.id()).thenReturn(UUID.randomUUID());
        when(questionVersions.findByQuestionIdOrderByVersionDesc(qid)).thenReturn(List.of(version));

        QuestionPart part = mock(QuestionPart.class);
        when(part.label()).thenReturn("a");
        MarkPoint point = mock(MarkPoint.class);
        when(point.questionPart()).thenReturn(part);
        when(point.ref()).thenReturn("a-1");
        when(point.text()).thenReturn("hydrogen");
        when(point.marks()).thenReturn(1);
        when(point.acceptanceCriteria()).thenReturn(List.of("H2"));
        MarkScheme scheme = mock(MarkScheme.class);
        when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.VALIDATED);
        when(scheme.points()).thenReturn(List.of(point));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(scheme));

        var view = service.preview(root, List.of(topicA), 20, null, true);

        assertThat(view.questions()).hasSize(1);
        var assembled = view.questions().get(0);
        assertThat(assembled.answers()).hasSize(1);
        assertThat(assembled.answers().get(0).partLabel()).isEqualTo("a");
        assertThat(assembled.answers().get(0).text()).isEqualTo("hydrogen");
        assertThat(assembled.answers().get(0).acceptanceCriteria()).containsExactly("H2");
        assertThat(assembled.schemeState()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("boundary holds: only what the serving boundary returns can enter a test")
    void boundaryIsTheOnlySource() {
        givenSubject();
        when(servableQuestions.activeByTopic(Mockito.any(UUID.class))).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(0);

        var view = service.preview(root, List.of(topicA, topicB), 20, null, true);

        assertThat(view.questionCount()).isZero();
        assertThat(view.totalMarks()).isZero();
        assertThat(view.questions()).isEmpty();
    }

    // -- marks-aware assembly (V2, productization §5) ------------------------

    @Test
    @DisplayName("targetMarks: greedy difficulty-ordered fill up to the target")
    void marksTargetGreedyFill() {
        givenSubject();
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        UUID q3 = UUID.randomUUID();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                question(q1, 1, 4), question(q2, 2, 6), question(q3, 3, 8)));
        when(servableQuestions.activeByTopic(topicB)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(3);
        when(servableQuestions.countServableByTopic(topicB)).thenReturn(0);

        // 4 + 6 = 10 fits; the 8-mark question would overshoot to 18
        var view = service.preview(root, List.of(topicA), 20, 10, false);
        assertThat(view.totalMarks()).isEqualTo(10);
        assertThat(view.targetMarks()).isEqualTo(10);
        assertThat(view.questions()).extracting(q -> q.id()).containsExactly(q1, q2);
    }

    @Test
    @DisplayName("targetMarks: smallest overshoot closes an unreachable target deterministically")
    void marksTargetSmallestOvershoot() {
        givenSubject();
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                question(q1, 1, 6), question(q2, 2, 9)));
        when(servableQuestions.activeByTopic(topicB)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(2);
        when(servableQuestions.countServableByTopic(topicB)).thenReturn(0);

        // target 10: 6 fits, then 9 overshoots to 15 — but 15 is closer than
        // stopping at 6 (|15-10|=5 < |6-10|=4? no: 4 < 5 — stopping short wins)
        // exact policy: while short, add the candidate landing CLOSEST to the
        // target; 6+9=15 distance 5 vs staying at 6 distance 4 -> do NOT add.
        var view = service.preview(root, List.of(topicA), 20, 10, false);
        assertThat(view.totalMarks()).isEqualTo(6);

        // target 11: 6+9=15 distance 4 vs staying at 6 distance 5 -> add: 15
        var view2 = service.preview(root, List.of(topicA), 20, 11, false);
        assertThat(view2.totalMarks()).isEqualTo(15);
    }

    @Test
    @DisplayName("targetMarks respects the question-count cap as a hard bound")
    void marksTargetRespectsCap() {
        givenSubject();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                question(UUID.randomUUID(), 1, 2), question(UUID.randomUUID(), 2, 2),
                question(UUID.randomUUID(), 3, 2), question(UUID.randomUUID(), 4, 2)));
        when(servableQuestions.activeByTopic(topicB)).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(4);
        when(servableQuestions.countServableByTopic(topicB)).thenReturn(0);

        var view = service.preview(root, List.of(topicA), 2, 100, false);
        assertThat(view.questions()).hasSize(2);   // cap wins over the marks target
        assertThat(view.totalMarks()).isEqualTo(4);
    }

    // ── sprint-2 §10: class-weakness targeting options ──────────────────

    private ClassAnalyticsService.TopicAggregateView topicAgg(UUID id, String code,
                                                               int measured, Double mean,
                                                               String band, int miscoLearners,
                                                               int attempts, int servable) {
        return new ClassAnalyticsService.TopicAggregateView(id, code, "title " + code,
                null, null, measured, mean, band, attempts, miscoLearners,
                Math.max(0, miscoLearners), 0, 0, servable);
    }

    private ClassOverviewView overviewWith(List<ClassAnalyticsService.TopicAggregateView> topics,
                                            List<ClassAnalyticsService.WeakPrerequisiteView> weakPrereqs) {
        return new ClassOverviewView(root, "4CH1", ClassAnalyticsService.POLICY,
                30, 12, 5, topics.size(), (int) topics.stream()
                        .filter(t -> t.learnersMeasured() > 0).count(),
                topics, weakPrereqs,
                new ClassAnalyticsService.RecentActivityView(20, 5, 8, 3, Instant.now()));
    }

    @Test
    @DisplayName("weakness options: transparent reasons, no synthetic score, honest coverage gaps")
    void weaknessOptionsDeriveExplicitReasons() {
        UUID weakMastery = UUID.randomUUID();
        UUID miscoOnly = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        UUID strong = UUID.randomUUID();
        UUID gap = UUID.randomUUID();
        UUID silentGap = UUID.randomUUID();
        when(classAnalytics.overview(root)).thenReturn(overviewWith(List.of(
                topicAgg(weakMastery, "4CH1-S1-a", 4, 0.30, "LOW", 0, 9, 6),
                topicAgg(miscoOnly, "4CH1-S2-b", 0, null, "UNMEASURED", 2, 0, 4),
                topicAgg(blocked, "4CH1-S3-a", 3, 0.55, "DEVELOPING", 0, 7, 3),
                topicAgg(strong, "4CH1-S4-a", 5, 0.82, "SECURE", 0, 12, 5),
                topicAgg(gap, "4CH1-S5-a", 0, null, "UNMEASURED", 0, 4, 5),
                topicAgg(silentGap, "4CH1-S6-a", 0, null, "UNMEASURED", 0, 0, 2)),
                List.of(new ClassAnalyticsService.WeakPrerequisiteView(
                        weakMastery, "4CH1-S1-a", "title 4CH1-S1-a", 4, 0.30, "LOW",
                        List.of(new ClassAnalyticsService.DependentView(
                                blocked, "4CH1-S3-a", "title 4CH1-S3-a", 0.55))))));
        // targeting counts (builder rule): one question primary-mapped to the
        // weak-mastery topic, one to the gap topic, and the SAME question
        // secondary-mapped to the misconception-only topic. The mapping row is
        // built BEFORE the when() chain — never mock inside thenReturn args.
        UUID sharedQuestion = UUID.randomUUID();
        QuestionTopic miscoMapping = mapping(sharedQuestion, miscoOnly);
        when(servableQuestions.activeWithin(any())).thenReturn(List.of(
                questionOn(UUID.randomUUID(), weakMastery),
                questionOn(sharedQuestion, gap)));
        when(questionTopics.findByQuestionIdIn(any()))
                .thenReturn(List.of(miscoMapping));

        var view = service.weaknessOptions(root);

        assertThat(view.policy()).isEqualTo("test-builder-weakness/v1");
        assertThat(view.weakTopics()).extracting("code")
                .containsExactly("4CH1-S1-a", "4CH1-S3-a", "4CH1-S2-b");
        // most-weak first (mean asc, nulls last); the blocked dependent carries its blocker code
        var first = view.weakTopics().get(0);
        assertThat(first.reasons()).containsExactly("LOW_MEAN_MASTERY");
        assertThat(first.meanMastery()).isEqualTo(0.30);
        assertThat(first.servableQuestions()).isEqualTo(1);   // the targeting count, not the analytics count
        var blockedOption = view.weakTopics().get(1);
        assertThat(blockedOption.reasons()).containsExactly("BLOCKED_BY_WEAK_PREREQUISITE");
        assertThat(blockedOption.blockedByPrerequisiteCodes()).containsExactly("4CH1-S1-a");
        var miscoOption = view.weakTopics().get(2);
        assertThat(miscoOption.reasons()).containsExactly("ACTIVE_MISCONCEPTION_PRESENT");
        assertThat(miscoOption.learnersWithActiveMisconception()).isEqualTo(2);
        assertThat(miscoOption.servableQuestions()).isEqualTo(1);   // counted through the SECONDARY mapping
        // the strong topic is not offered; the silent unmeasured topic (no activity) is not a gap
        assertThat(view.weakTopics()).noneMatch(o -> o.topicNodeId().equals(strong));
        assertThat(view.coverageGaps()).extracting("topicNodeId")
                .containsExactly(gap);   // activity + targetable content but unmeasured — honest gap, never weak
        assertThat(view.coverageGaps().get(0).servableQuestions()).isEqualTo(1);
        assertThat(view.selectionHint()).contains("preview");
    }

    @Test
    @DisplayName("weakness options: deterministic ordering across equal means")
    void weaknessOptionsOrderDeterministically() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(classAnalytics.overview(root)).thenReturn(overviewWith(List.of(
                topicAgg(c, "4CH1-S2-a", 2, 0.35, "LOW", 1, 5, 4),
                topicAgg(a, "4CH1-S1-a", 2, 0.35, "LOW", 3, 5, 4),
                topicAgg(b, "4CH1-S3-a", 2, 0.20, "LOW", 0, 5, 4)),
                List.of()));

        var view = service.weaknessOptions(root);

        // mean asc (b first); equal means order by misconception learners DESC (a before c)
        assertThat(view.weakTopics()).extracting("code")
                .containsExactly("4CH1-S3-a", "4CH1-S1-a", "4CH1-S2-a");
    }

    @Test
    @DisplayName("weakness options: an empty class evidence base yields empty options, honestly")
    void weaknessOptionsEmptyStateIsHonest() {
        when(classAnalytics.overview(root)).thenReturn(overviewWith(List.of(), List.of()));

        var view = service.weaknessOptions(root);

        assertThat(view.weakTopics()).isEmpty();
        assertThat(view.coverageGaps()).isEmpty();
        assertThat(view.selectionHint()).contains("preview");
    }
}
