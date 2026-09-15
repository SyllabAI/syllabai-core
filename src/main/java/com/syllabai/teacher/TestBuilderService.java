package com.syllabai.teacher;

import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.recommendation.RecommendationProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test Builder (P9 smallest-useful-version): assemble a printable topic test
 * from VALIDATED content only. The assembly reuses {@link ServableQuestionService}
 * — the ONE learner-serving boundary — so a generated test can never contain
 * SUGGESTED/FLAGGED/REJECTED content, and the V20 paper gate applies as usual.
 *
 * <p>Scope deliberately minimal: topic selection -> difficulty-ordered
 * question list with marks and parts, optional answer key from the mark
 * schemes (teacher-only surface; scheme validation state is reported so the
 * teacher knows what they are printing). No persistence, no scheduling, no
 * IRT assembly — those are later cycles.</p>
 */
@Service
@Transactional(readOnly = true)
public class TestBuilderService {

    private static final int DEFAULT_MAX = 20;
    private static final int HARD_MAX = 50;
    private static final int HARD_MAX_MARKS = 200;

    /** sprint-2 §10: class-weakness selection policy id */
    public static final String WEAKNESS_POLICY = "test-builder-weakness/v1";

    private final ServableQuestionService servableQuestions;
    private final KnowledgeGraphService knowledgeGraph;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final ClassAnalyticsService classAnalytics;
    private final RecommendationProperties properties;
    private final QuestionTopicRepository questionTopics;

    public TestBuilderService(ServableQuestionService servableQuestions,
                              KnowledgeGraphService knowledgeGraph,
                              QuestionVersionRepository questionVersions,
                              MarkSchemeRepository markSchemes,
                              ClassAnalyticsService classAnalytics,
                              RecommendationProperties properties,
                              QuestionTopicRepository questionTopics) {
        this.servableQuestions = servableQuestions;
        this.knowledgeGraph = knowledgeGraph;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.classAnalytics = classAnalytics;
        this.properties = properties;
        this.questionTopics = questionTopics;
    }

    /**
     * @param rootId        the subject's KG root — hard scope: only topics inside
     *                      its PART_OF subtree are honoured (subject isolation)
     * @param topicNodeIds  selected curriculum topics
     * @param maxQuestions  cap on assembled questions (clamped 1..50, default 20)
     * @param targetMarks   optional marks-aware assembly target (clamped 1..200):
     *                      questions are picked greedily in difficulty order while
     *                      the cumulative marks stay within the target; when the
     *                      target cannot be met exactly, the smallest-overshoot
     *                      candidate closes the gap (deterministic, documented in
     *                      the response). maxQuestions still applies as a hard cap.
     * @param includeAnswers attach the current mark scheme's points per question
     */
    public TestPreviewView preview(UUID rootId, List<UUID> topicNodeIds,
                                   Integer maxQuestions, Integer targetMarks,
                                   boolean includeAnswers) {
        Set<UUID> subtree = new HashSet<>(knowledgeGraph.subtreeIds(rootId));
        List<UUID> topics = topicNodeIds == null ? List.of()
                : topicNodeIds.stream().filter(subtree::contains).distinct().toList();

        // topic code lookup for attribution + coverage summary
        Map<UUID, String> codeByTopic = new HashMap<>();
        Map<UUID, String> titleByTopic = new HashMap<>();
        for (UUID topic : topics) {
            var node = knowledgeGraph.node(topic);
            codeByTopic.put(topic, node.code());
            titleByTopic.put(topic, node.title());
        }

        // assemble through the serving boundary; first selected topic wins attribution
        LinkedHashMap<UUID, StudentQuestionView> byQuestion = new LinkedHashMap<>();
        Map<UUID, UUID> topicByQuestion = new HashMap<>();
        for (UUID topic : topics) {
            for (StudentQuestionView q : servableQuestions.activeByTopic(topic)) {
                if (byQuestion.putIfAbsent(q.id(), q) == null) {
                    topicByQuestion.put(q.id(), topic);
                }
            }
        }

        int cap = maxQuestions == null ? DEFAULT_MAX
                : Math.max(1, Math.min(HARD_MAX, maxQuestions));
        Integer marksTarget = targetMarks == null ? null
                : Math.max(1, Math.min(HARD_MAX_MARKS, targetMarks));
        List<StudentQuestionView> difficultyOrdered = byQuestion.values().stream()
                .sorted(Comparator.comparingInt(StudentQuestionView::difficulty)
                        .thenComparing(StudentQuestionView::id))
                .toList();
        List<StudentQuestionView> selected = selectByMarks(difficultyOrdered, cap, marksTarget);

        // per-topic availability BEFORE the cap (the teacher needs the honest number)
        Map<UUID, Integer> availableByTopic = new LinkedHashMap<>();
        for (UUID topic : topics) {
            availableByTopic.put(topic, servableQuestions.countServableByTopic(topic));
        }

        int totalMarks = selected.stream().mapToInt(StudentQuestionView::marks).sum();

        List<TestQuestionView> questions = new ArrayList<>(selected.size());
        for (StudentQuestionView q : selected) {
            UUID topic = topicByQuestion.get(q.id());
            List<TestAnswerView> answers = List.of();
            String schemeState = null;
            if (includeAnswers && "STRUCTURED".equals(q.type())) {
                var current = questionVersions
                        .findByQuestionIdOrderByVersionDesc(q.id()).stream().findFirst().orElse(null);
                if (current != null) {
                    MarkScheme scheme = markSchemes
                            .findFirstByQuestionVersionIdOrderByCreatedAtDesc(current.id()).orElse(null);
                    if (scheme != null) {
                        schemeState = scheme.validationState().name();
                        answers = scheme.points().stream()
                                .map(p -> new TestAnswerView(
                                        p.questionPart() == null ? null : p.questionPart().label(),
                                        p.ref(), p.text(), p.marks(), p.acceptanceCriteria()))
                                .toList();
                    }
                }
            }
            questions.add(new TestQuestionView(
                    q.id(), q.type(), q.stem(), q.marks(), q.commandWord(), q.difficulty(),
                    codeByTopic.getOrDefault(topic, null),
                    q.parts(), q.options(), answers, schemeState));
        }

        List<TopicCoverage> coverage = topics.stream()
                .map(t -> new TopicCoverage(
                        t, codeByTopic.get(t), titleByTopic.get(t),
                        availableByTopic.getOrDefault(t, 0)))
                .toList();

        return new TestPreviewView(rootId, selected.size(), totalMarks, marksTarget,
                coverage, questions);
    }

    /**
     * Sprint-2 §10: class-weakness targeting options — the class-evidence
     * topics a teacher can hand straight to {@link #preview} as selection
     * constraints.
     *
     * <p>No synthetic weakness score: every option carries ONLY the measured
     * aggregates it was derived from plus a list of explicit reasons —
     * LOW_MEAN_MASTERY (class mean below the weak ceiling with at least one
     * measured learner), ACTIVE_MISCONCEPTION_PRESENT (at least one learner
     * with an active BDT misconception on the topic), BLOCKED_BY_WEAK_PREREQUISITE
     * (the class-analytics weak-prerequisite view names this topic as a
     * dependent). Unmeasured topics are NEVER claimed weak — they are listed
     * separately as coverage gaps ("insufficient coverage") for the teacher's
     * own judgment.</p>
     *
     * <p>{@code servableQuestions} on every option is the TARGETING count —
     * validated questions reachable from the topic through primary OR secondary
     * mappings, the exact rule {@code /preview} assembles by (two batched
     * queries; NOT the primary-keyed analytics count, which would understate
     * what a targeted test can draw on). Deterministic ordering: meanMastery
     * ascending (nulls last), then learners-with-active-misconception
     * descending, then code ascending.</p>
     */
    public WeaknessOptionsView weaknessOptions(UUID rootId) {
        ClassAnalyticsService.ClassOverviewView overview = classAnalytics.overview(rootId);

        // weak prerequisites (class-level): dependent topic -> weak prerequisite codes
        Map<UUID, List<String>> blockedBy = new HashMap<>();
        for (ClassAnalyticsService.WeakPrerequisiteView wp : overview.weakPrerequisites()) {
            for (ClassAnalyticsService.DependentView d : wp.dependents()) {
                blockedBy.computeIfAbsent(d.nodeId(), k -> new ArrayList<>())
                        .add(wp.prerequisiteCode());
            }
        }

        // first pass: derive candidates from the class evidence
        Map<UUID, List<String>> reasonsByTopic = new HashMap<>();
        List<ClassAnalyticsService.TopicAggregateView> weakCandidates = new ArrayList<>();
        List<ClassAnalyticsService.TopicAggregateView> gapCandidates = new ArrayList<>();
        for (ClassAnalyticsService.TopicAggregateView t : overview.topics()) {
            List<String> reasons = new ArrayList<>();
            if (t.learnersMeasured() > 0 && t.meanMastery() != null
                    && t.meanMastery() < properties.weakMasteryCeiling()) {
                reasons.add("LOW_MEAN_MASTERY");
            }
            if (t.learnersWithActiveMisconception() > 0) {
                reasons.add("ACTIVE_MISCONCEPTION_PRESENT");
            }
            if (blockedBy.containsKey(t.nodeId())) {
                reasons.add("BLOCKED_BY_WEAK_PREREQUISITE");
            }
            if (!reasons.isEmpty()) {
                reasonsByTopic.put(t.nodeId(), reasons);
                weakCandidates.add(t);
            } else if (t.learnersMeasured() == 0
                    && (t.evidenceBackedAttempts() > 0 || t.tutorEngagements() > 0)) {
                // unmeasured but with some class activity worth noticing — an
                // honest coverage-gap candidate, never claimed weak
                gapCandidates.add(t);
            }
        }

        // second pass: the targeting counts (builder rule, two batched queries)
        Set<UUID> scope = new HashSet<>();
        weakCandidates.forEach(t -> scope.add(t.nodeId()));
        gapCandidates.forEach(t -> scope.add(t.nodeId()));
        Map<UUID, Integer> targetingCounts = targetingServableCounts(scope);

        List<WeakTopicOption> weak = new ArrayList<>();
        for (ClassAnalyticsService.TopicAggregateView t : weakCandidates) {
            weak.add(new WeakTopicOption(t.nodeId(), t.code(), t.title(),
                    reasonsByTopic.get(t.nodeId()),
                    t.learnersMeasured(), t.meanMastery(), t.masteryBand(),
                    t.learnersWithActiveMisconception(), t.activeMisconceptionSignals(),
                    t.evidenceBackedAttempts(), t.tutorEngagements(), t.dueReviews(),
                    targetingCounts.getOrDefault(t.nodeId(), 0),
                    List.copyOf(blockedBy.getOrDefault(t.nodeId(), List.of()))));
        }
        List<CoverageGapView> gaps = new ArrayList<>();
        for (ClassAnalyticsService.TopicAggregateView t : gapCandidates) {
            int targetable = targetingCounts.getOrDefault(t.nodeId(), 0);
            if (targetable > 0) {
                gaps.add(new CoverageGapView(t.nodeId(), t.code(), t.title(),
                        targetable, t.evidenceBackedAttempts(), t.tutorEngagements()));
            }
        }
        weak.sort(Comparator
                .comparing(WeakTopicOption::meanMastery,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparingInt(WeakTopicOption::learnersWithActiveMisconception)
                        .reversed())
                .thenComparing(WeakTopicOption::code));

        return new WeaknessOptionsView(rootId, WEAKNESS_POLICY,
                overview.enrolledLearners(), overview.learnersWithEvidence(),
                List.copyOf(weak), List.copyOf(gaps),
                "Pass weakTopics[].topicNodeId values as topicNodeIds to GET /api/v1/teacher/tests/preview"
                        + " — assembly stays VALIDATED-only, marks-targeted and deterministic.");
    }

    /**
     * Per-topic counts of servable questions reachable through primary OR
     * secondary mappings — the /preview assembly rule, batched: one
     * activeWithin query over the scope plus one QuestionTopic fetch over the
     * returned question ids. No per-topic queries (§14).
     */
    private Map<UUID, Integer> targetingServableCounts(Collection<UUID> topicIds) {
        if (topicIds.isEmpty()) {
            return Map.of();
        }
        List<StudentQuestionView> servable = servableQuestions.activeWithin(topicIds);
        Map<UUID, Set<UUID>> byTopic = new HashMap<>();
        Set<UUID> questionIds = new HashSet<>();
        for (StudentQuestionView q : servable) {
            questionIds.add(q.id());
            if (topicIds.contains(q.primaryTopicNodeId())) {
                byTopic.computeIfAbsent(q.primaryTopicNodeId(), k -> new HashSet<>())
                        .add(q.id());
            }
        }
        if (!questionIds.isEmpty()) {
            for (QuestionTopic qt : questionTopics.findByQuestionIdIn(questionIds)) {
                if (topicIds.contains(qt.nodeId())) {
                    byTopic.computeIfAbsent(qt.nodeId(), k -> new HashSet<>())
                            .add(qt.question().id());
                }
            }
        }
        Map<UUID, Integer> counts = new HashMap<>();
        byTopic.forEach((topic, ids) -> counts.put(topic, ids.size()));
        return counts;
    }

    /**
     * Deterministic marks-aware selection:
     * <ol>
     *   <li>no target — the P9 behaviour: first {@code cap} questions in
     *       difficulty order;</li>
     *   <li>with a target — greedy pass in difficulty order adding every
     *       question that fits within the target; then, while the total is
     *       still short, repeatedly add the remaining candidate that lands
     *       closest to the target (the smallest overshoot when an exact fit
     *       is impossible). The cap always applies.</li>
     * </ol>
     */
    private static List<StudentQuestionView> selectByMarks(List<StudentQuestionView> ordered,
                                                           int cap, Integer target) {
        if (target == null) {
            return ordered.stream().limit(cap).toList();
        }
        List<StudentQuestionView> remaining = new ArrayList<>(ordered);
        List<StudentQuestionView> chosen = new ArrayList<>();
        int total = 0;
        for (StudentQuestionView q : ordered) {
            if (chosen.size() >= cap || remaining.isEmpty()) {
                break;
            }
            if (total + q.marks() <= target) {
                chosen.add(q);
                remaining.remove(q);
                total += q.marks();
            }
        }
        while (total < target && chosen.size() < cap && !remaining.isEmpty()) {
            StudentQuestionView best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (StudentQuestionView q : remaining) {
                int distance = Math.abs(total + q.marks() - target);
                if (distance < bestDistance) {
                    best = q;
                    bestDistance = distance;
                }
            }
            // add only when the addition lands strictly closer to the target
            // than stopping short — overshooting for its own sake is worse
            // than an honest undershoot
            if (bestDistance >= target - total) {
                break;
            }
            chosen.add(best);
            remaining.remove(best);
            total += best.marks();
        }
        return chosen;
    }

    /**
     * the assembled test: validated questions only, difficulty-ordered.
     * {@code targetMarks} echoes the requested marks target (null = question-count mode).
     */
    public record TestPreviewView(
            UUID rootId, int questionCount, int totalMarks, Integer targetMarks,
            List<TopicCoverage> topics, List<TestQuestionView> questions) {
    }

    public record TopicCoverage(UUID topicNodeId, String code, String title, int servableQuestions) {
    }

    /** print-shaped question: body + parts, answers only when requested */
    public record TestQuestionView(
            UUID id, String type, String stem, int marks, String commandWord, int difficulty,
            String topicCode, List<StudentQuestionView.PartView> parts,
            List<StudentQuestionView.OptionView> options,
            List<TestAnswerView> answers, String schemeState) {
    }

    /** one mark point of the answer key (teacher-only) */
    public record TestAnswerView(String partLabel, String ref, String text, int marks,
                                 List<String> acceptanceCriteria) {
    }

    // ══ sprint-2 §10: class-weakness targeting (read-only options) ══════════

    /** class-weakness options derived transparently from the class evidence */
    public record WeaknessOptionsView(
            UUID rootId,
            String policy,
            int enrolledLearners,
            int learnersWithEvidence,
            List<WeakTopicOption> weakTopics,
            List<CoverageGapView> coverageGaps,
            String selectionHint) {
    }

    /**
     * One weak class area with the EXPLICIT reasons it is considered weak and
     * the raw aggregates behind them — no composite score. Ordering key:
     * meanMastery asc (nulls last) → active-misconception learners desc → code.
     */
    public record WeakTopicOption(
            UUID topicNodeId, String code, String title, List<String> reasons,
            int learnersMeasured, Double meanMastery, String masteryBand,
            int learnersWithActiveMisconception, int activeMisconceptionSignals,
            int evidenceBackedAttempts, int tutorEngagements, int dueReviews,
            int servableQuestions, List<String> blockedByPrerequisiteCodes) {
    }

    /**
     * An unmeasured topic with servable content — an honest "insufficient
     * coverage" candidate for the teacher's judgment, never claimed weak.
     */
    public record CoverageGapView(
            UUID topicNodeId, String code, String title, int servableQuestions,
            int evidenceBackedAttempts, int tutorEngagements) {
    }
}
