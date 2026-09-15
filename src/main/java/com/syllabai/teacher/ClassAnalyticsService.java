package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.TutorTopicEngagement;
import com.syllabai.learner.TutorTopicEngagementRepository;
import com.syllabai.recommendation.RecommendationProperties;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher class intelligence (productization sprint 2 §2–§5): the smallest
 * real production version of
 * <em>student attempts → learning evidence → learner state → class-level
 * aggregation → teacher insight → intervention</em>.
 *
 * <p><b>Evidence semantics (non-negotiable, §4).</b> Every aggregate is built
 * from the EXISTING evidence tables and stays separated by kind: raw attempt
 * activity (recent window), evidence-backed practice (skill_states rows exist
 * only where BKT reacted to graded evidence), tutor engagement (interest or
 * doubt signal — <em>never</em> mastery), BKT mastery estimates, BDT
 * misconception probabilities, coverage (measured vs total topics) and review
 * state. Asking the Tutor about a topic is NOT weakness; it is reported as
 * engagement, in its own fields. Unmeasured is represented honestly: means
 * are {@code null} and bands read {@code UNMEASURED} — nothing is fabricated
 * for learners or topics without evidence.</p>
 *
 * <p><b>Read-only by construction.</b> The service never writes: class
 * analytics consume the learner model, they do not become a second
 * implementation of it. Mastery bands and weakness thresholds are the SAME
 * parameters the learner-facing surfaces use ({@link LearnerProperties} decay
 * bands and BDT active threshold, {@link RecommendationProperties}
 * weak-mastery ceiling and evidence floor) — no teacher-specific tuning.</p>
 *
 * <p><b>Batched by construction (§11).</b> One tree query, then ONE query per
 * evidence table for the WHOLE class; every per-topic and per-learner
 * aggregate is grouped in memory. No per-learner or per-topic loop touches
 * the database.</p>
 */
@Service
public class ClassAnalyticsService {

    /** read-model marker — the deterministic aggregation contract */
    public static final String POLICY = "class-analytics/v1";

    private static final int RECENT_WINDOW_DAYS = 14;
    private static final int WEAK_PREREQUISITE_CAP = 10;
    private static final int WEAKEST_TOPICS_PER_LEARNER = 3;
    private static final int MISCONCEPTION_SIGNALS_PER_LEARNER = 5;
    private static final int DRILL_DOWN_EVIDENCE_CAP = 20;

    private final KnowledgeGraphService graph;
    private final SkillStateRepository skillStates;
    private final MisconceptionStateRepository misconceptionStates;
    private final TutorTopicEngagementRepository engagements;
    private final ReviewScheduleRepository reviewSchedules;
    private final AttemptRepository attempts;
    private final AnswerRepository answers;
    private final UserRepository users;
    private final ServableQuestionService servableQuestions;
    private final LearnerProperties learnerProperties;
    private final RecommendationProperties properties;

    public ClassAnalyticsService(KnowledgeGraphService graph,
                                 SkillStateRepository skillStates,
                                 MisconceptionStateRepository misconceptionStates,
                                 TutorTopicEngagementRepository engagements,
                                 ReviewScheduleRepository reviewSchedules,
                                 AttemptRepository attempts,
                                 AnswerRepository answers,
                                 UserRepository users,
                                 ServableQuestionService servableQuestions,
                                 LearnerProperties learnerProperties,
                                 RecommendationProperties properties) {
        this.graph = graph;
        this.skillStates = skillStates;
        this.misconceptionStates = misconceptionStates;
        this.engagements = engagements;
        this.reviewSchedules = reviewSchedules;
        this.attempts = attempts;
        this.answers = answers;
        this.users = users;
        this.servableQuestions = servableQuestions;
        this.learnerProperties = learnerProperties;
        this.properties = properties;
    }

    // ═══════════════════════════ read models ═══════════════════════════

    /** §2 class overview: cohort, coverage, per-topic aggregates, weak prerequisites, activity */
    @Transactional(readOnly = true)
    public ClassOverviewView overview(UUID rootId) {
        Scope scope = scope(rootId);
        Aggregates agg = aggregates(scope, Instant.now());

        List<User> roster = users.findEnabledByRole(Role.STUDENT);

        // per-topic aggregates (the heatmap cells), curriculum order preserved
        List<TopicAggregateView> topics = new ArrayList<>();
        for (NodeView node : scope.structureById().values()) {
            topics.add(topicAggregate(node, scope, agg));
        }

        List<WeakPrerequisiteView> weak = weakPrerequisites(rootId, scope, agg);

        Set<UUID> withEvidence = new HashSet<>();
        agg.skills().forEach(s -> withEvidence.add(s.learnerId()));
        agg.misconceptions().forEach(m -> withEvidence.add(m.learnerId()));
        agg.engagements().forEach(e -> withEvidence.add(e.learnerId()));

        int measuredTopics = (int) topics.stream()
                .filter(t -> t.learnersMeasured() > 0).count();

        return new ClassOverviewView(
                rootId, scope.rootCode(), POLICY,
                roster.size(), withEvidence.size(), agg.recentByLearner().size(),
                scope.structureById().size(), measuredTopics,
                topics, weak,
                new RecentActivityView(
                        agg.recentTotal(), agg.recentByLearner().size(),
                        agg.engagements().size(),
                        (int) answers.countByMarkingStateWithin(
                                Answer.MarkingState.PENDING, scope.structureIds()),
                        agg.windowStart()));
    }

    /** §2 learner list: evidence-separated per-learner rows for the class */
    @Transactional(readOnly = true)
    public List<ClassLearnerView> learners(UUID rootId) {
        Scope scope = scope(rootId);
        Aggregates agg = aggregates(scope, Instant.now());
        Map<UUID, List<SkillState>> skillsByLearner = new HashMap<>();
        for (SkillState s : agg.skills()) {
            skillsByLearner.computeIfAbsent(s.learnerId(), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, List<MisconceptionState>> miscoByLearner = new HashMap<>();
        for (MisconceptionState m : agg.misconceptions()) {
            miscoByLearner.computeIfAbsent(m.learnerId(), k -> new ArrayList<>()).add(m);
        }
        Map<UUID, List<TutorTopicEngagement>> engagementByLearner = new HashMap<>();
        for (TutorTopicEngagement e : agg.engagements()) {
            engagementByLearner.computeIfAbsent(e.learnerId(), k -> new ArrayList<>()).add(e);
        }
        Map<UUID, Integer> dueByLearner = new HashMap<>();
        for (ReviewSchedule r : agg.dueReviews()) {
            dueByLearner.merge(r.learnerId(), 1, Integer::sum);
        }

        List<ClassLearnerView> rows = new ArrayList<>();
        for (User u : users.findEnabledByRole(Role.STUDENT)) {
            rows.add(learnerRow(u, scope, agg, skillsByLearner, miscoByLearner,
                    engagementByLearner, dueByLearner));
        }
        // deterministic order: measured learners first, weakest mean mastery
        // first (the teacher's attention queue), then unmeasured by name
        rows.sort(Comparator
                .comparing((ClassLearnerView r) -> r.evidenceState().equals("UNMEASURED"))
                .thenComparing(r -> r.meanMastery() == null ? 0.0 : r.meanMastery())
                .thenComparing(r -> r.displayName() == null ? "" : r.displayName().toLowerCase()));
        return rows;
    }

    /** §5 topic drill-down: class → topic → learners → evidence → intervention */
    @Transactional(readOnly = true)
    public TopicDrillDownView topicDrillDown(UUID rootId, UUID nodeId) {
        Scope scope = scope(rootId);
        NodeView topic = scope.structureById().get(nodeId);
        if (topic == null) {
            // hard subject isolation: a topic outside this root is a 404
            throw new NotFoundException("curriculum topic in this subject", nodeId);
        }
        Aggregates agg = aggregates(scope, Instant.now());

        TopicAggregateView aggregate = topicAggregate(topic, scope, agg);

        // prerequisite chain with the class-level mastery per link
        // (KG direction: dependent → prerequisite; the chain lists prerequisites)
        List<PrerequisiteLinkView> chain = graph.prerequisiteChain(nodeId).stream()
                .map(p -> {
                    PerTopic t = agg.byTopic().get(p.id());
                    return new PrerequisiteLinkView(p.id(), p.code(), p.title(), p.depth(),
                            t == null ? 0 : t.learnersMeasured(),
                            t == null || t.learnersMeasured() == 0 ? null : t.meanMastery(),
                            t == null || t.learnersMeasured() == 0
                                    ? "UNMEASURED" : bandOf(t.meanMastery()));
                })
                .toList();

        List<AffectedLearnerView> affected = affectedLearners(topic, scope, agg);
        List<EvidenceItemView> evidence = representativeEvidence(nodeId);

        List<ServableQuestionRef> servable = servableQuestions.activeByTopic(nodeId).stream()
                .map(q -> new ServableQuestionRef(q.id(), q.externalRef(), q.type(),
                        q.marks(), q.difficulty()))
                .toList();

        return new TopicDrillDownView(rootId, aggregate, chain, affected, evidence, servable);
    }

    // ═══════════════════════════ view records ═══════════════════════════

    /** one heatmap cell: topic / specification point × class evidence and mastery */
    public record TopicAggregateView(
            UUID nodeId, String code, String title, String parentCode, String parentTitle,
            int learnersMeasured, Double meanMastery, String masteryBand,
            int evidenceBackedAttempts,
            int learnersWithActiveMisconception, int activeMisconceptionSignals,
            int tutorEngagements, int dueReviews, int servableQuestions) {
    }

    public record ClassOverviewView(
            UUID rootId, String rootCode, String policy,
            int enrolledLearners, int learnersWithEvidence, int learnersRecentlyActive,
            int totalTopics, int measuredTopics,
            List<TopicAggregateView> topics,
            List<WeakPrerequisiteView> weakPrerequisites,
            RecentActivityView recentActivity) {
    }

    /** a prerequisite the CLASS measures weak, with the dependents that need it */
    public record WeakPrerequisiteView(
            UUID prerequisiteNodeId, String prerequisiteCode, String prerequisiteTitle,
            int learnersMeasured, Double meanMastery, String masteryBand,
            List<DependentView> dependents) {
    }

    public record DependentView(UUID nodeId, String code, String title, Double meanMastery) {
    }

    public record RecentActivityView(
            int recentAttempts, int learnersActive, int tutorAsks,
            int structuredAnswersPendingMarking, Instant windowStart) {
    }

    /** one learner row — evidence kinds stay separated; nulls mean unmeasured */
    public record ClassLearnerView(
            UUID learnerId, String displayName, Instant createdAt, String evidenceState,
            int topicsMeasured, Double meanMastery, int evidenceBackedAttempts,
            int recentAttempts, int recentCorrect, Instant lastActivityAt,
            List<TopicMasteryView> weakestTopics,
            int activeMisconceptions, List<MisconceptionSignalView> misconceptionSignals,
            int tutorEngagements, Map<String, Integer> tutorSignalCounts,
            Instant lastTutorEngagementAt, int dueReviews) {
    }

    public record TopicMasteryView(
            UUID nodeId, String code, String title, double mastery, String band, int attempts) {
    }

    /** BDT estimate on a misconception node attached under a topic */
    public record MisconceptionSignalView(
            UUID misconceptionNodeId, String code, String title, double probability,
            int evidenceCount, UUID parentTopicNodeId, String parentTopicCode) {
    }

    public record TopicDrillDownView(
            UUID rootId, TopicAggregateView topic,
            List<PrerequisiteLinkView> prerequisiteChain,
            List<AffectedLearnerView> affectedLearners,
            List<EvidenceItemView> representativeEvidence,
            List<ServableQuestionRef> servableQuestions) {
    }

    /** one link of the KG prerequisite chain with the class-level mastery estimate */
    public record PrerequisiteLinkView(
            UUID nodeId, String code, String title, int depth,
            int learnersMeasured, Double meanMastery, String masteryBand) {
    }

    public record AffectedLearnerView(
            UUID learnerId, String displayName, Double mastery, String reason,
            List<MisconceptionSignalView> misconceptions) {
    }

    /** one recent attempt — the raw evidence a teacher inspects before intervening */
    public record EvidenceItemView(
            UUID attemptId, UUID learnerId, String learnerDisplayName,
            UUID questionId, String questionRef, boolean correct, Integer marksAwarded,
            int questionMarks, String markingState, Instant createdAt) {
    }

    public record ServableQuestionRef(
            UUID id, String externalRef, String type, int marks, int difficulty) {
    }

    // ═══════════════════════════ internals ═══════════════════════════

    /** subject scope: the PART_OF tree registries (one batched tree query) */
    private record Scope(
            UUID rootId,
            String rootCode,
            Map<UUID, NodeView> structureById,           // PART_OF nodes, curriculum order
            Map<UUID, UUID> parentOf,
            Map<UUID, NodeView> misconceptionById,       // misconception node registry
            Map<UUID, UUID> misconceptionParentOf,        // misconception → its topic
            Map<UUID, List<NodeView>> misconceptionsOf,   // topic → misconception children
            Set<UUID> structureIds) {
    }

    /** the class's whole evidence base — one query per table, grouped in memory */
    private record Aggregates(
            List<SkillState> skills,
            List<MisconceptionState> misconceptions,
            List<TutorTopicEngagement> engagements,
            List<ReviewSchedule> dueReviews,
            Map<UUID, long[]> recentByLearner,           // learner → [attempts, correct]
            Map<UUID, Instant> lastActivityByLearner,
            int recentTotal,
            Instant windowStart,
            Map<UUID, Integer> servableByPrimaryTopic,   // primary topic → servable count
            Map<UUID, PerTopic> byTopic) {
    }

    /** in-memory per-topic rollup of the class's skill states */
    private record PerTopic(int learnersMeasured, Double meanMastery, int attempts,
                            List<SkillState> states) {
    }

    private Scope scope(UUID rootId) {
        NodeView tree = graph.treeWithMisconceptions(rootId);
        Map<UUID, NodeView> structureById = new LinkedHashMap<>();
        Map<UUID, UUID> parentOf = new HashMap<>();
        Map<UUID, NodeView> misconceptionById = new HashMap<>();
        Map<UUID, UUID> misconceptionParentOf = new HashMap<>();
        Map<UUID, List<NodeView>> misconceptionsOf = new HashMap<>();
        collect(tree, null, structureById, parentOf, misconceptionById,
                misconceptionParentOf, misconceptionsOf);
        return new Scope(rootId, tree.code(), structureById, parentOf, misconceptionById,
                misconceptionParentOf, misconceptionsOf, structureById.keySet());
    }

    private void collect(NodeView node, NodeView parent,
                         Map<UUID, NodeView> structureById, Map<UUID, UUID> parentOf,
                         Map<UUID, NodeView> misconceptionById,
                         Map<UUID, UUID> misconceptionParentOf,
                         Map<UUID, List<NodeView>> misconceptionsOf) {
        if ("MISCONCEPTION".equals(node.type())) {
            misconceptionById.put(node.id(), node);
            if (parent != null) {
                misconceptionParentOf.put(node.id(), parent.id());
                misconceptionsOf.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(node);
            }
            return;
        }
        structureById.put(node.id(), node);
        if (parent != null) {
            parentOf.put(node.id(), parent.id());
        }
        for (NodeView child : node.children()) {
            collect(child, node, structureById, parentOf, misconceptionById,
                    misconceptionParentOf, misconceptionsOf);
        }
    }

    private Aggregates aggregates(Scope scope, Instant now) {
        List<SkillState> skills = skillStates.findByNodeIdIn(scope.structureIds());
        List<MisconceptionState> misconceptions = misconceptionStates
                .findByMisconceptionNodeIdIn(scope.misconceptionById().keySet());
        List<TutorTopicEngagement> engagementRows = engagements
                .findByNodeIdIn(scope.structureIds());
        List<ReviewSchedule> due = reviewSchedules
                .findByStatusAndDueAtLessThanEqualAndNodeIdIn(
                        ReviewSchedule.Status.PENDING, now, scope.structureIds());

        Instant windowStart = now.minus(RECENT_WINDOW_DAYS, ChronoUnit.DAYS);
        Map<UUID, long[]> recentByLearner = new HashMap<>();
        Map<UUID, Instant> lastActivity = new HashMap<>();
        int recentTotal = 0;
        for (Object[] row : attempts.aggregateByLearnerSinceWithin(
                windowStart, scope.structureIds())) {
            UUID learnerId = (UUID) row[0];
            long total = ((Number) row[1]).longValue();
            recentByLearner.put(learnerId, new long[]{total, ((Number) row[2]).longValue()});
            lastActivity.put(learnerId, toInstant(row[3]));
            recentTotal += (int) total;
        }

        Map<UUID, Integer> servableByPrimary = new HashMap<>();
        for (StudentQuestionView q : servableQuestions.activeWithin(scope.structureIds())) {
            if (q.primaryTopicNodeId() != null) {
                servableByPrimary.merge(q.primaryTopicNodeId(), 1, Integer::sum);
            }
        }

        Map<UUID, List<SkillState>> byNode = new HashMap<>();
        for (SkillState s : skills) {
            byNode.computeIfAbsent(s.nodeId(), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, PerTopic> byTopic = new HashMap<>();
        for (Map.Entry<UUID, List<SkillState>> e : byNode.entrySet()) {
            List<SkillState> states = e.getValue();
            byTopic.put(e.getKey(), new PerTopic(
                    states.size(),
                    round(states.stream().mapToDouble(SkillState::mastery).average().orElse(0)),
                    states.stream().mapToInt(SkillState::attempts).sum(),
                    states));
        }

        return new Aggregates(skills, misconceptions, engagementRows, due,
                recentByLearner, lastActivity, recentTotal, windowStart,
                servableByPrimary, byTopic);
    }

    private TopicAggregateView topicAggregate(NodeView node, Scope scope, Aggregates agg) {
        PerTopic t = agg.byTopic().get(node.id());
        int learnersMeasured = t == null ? 0 : t.learnersMeasured();
        Double meanMastery = learnersMeasured == 0 ? null : t.meanMastery();

        // active misconception signals attached under this topic (BDT estimates)
        double threshold = learnerProperties.bdt().activeThreshold();
        Set<UUID> affectedLearners = new HashSet<>();
        int activeSignals = 0;
        for (NodeView misco : scope.misconceptionsOf().getOrDefault(node.id(), List.of())) {
            for (MisconceptionState m : agg.misconceptions()) {
                if (m.misconceptionNodeId().equals(misco.id())
                        && m.probability() >= threshold) {
                    activeSignals++;
                    affectedLearners.add(m.learnerId());
                }
            }
        }

        int engagementCount = (int) agg.engagements().stream()
                .filter(e -> e.nodeId().equals(node.id())).count();
        int due = (int) agg.dueReviews().stream()
                .filter(r -> r.nodeId().equals(node.id())).count();

        UUID parentId = scope.parentOf().get(node.id());
        NodeView parent = parentId == null ? null : scope.structureById().get(parentId);

        return new TopicAggregateView(
                node.id(), node.code(), node.title(),
                parent == null ? null : parent.code(),
                parent == null ? null : parent.title(),
                learnersMeasured, meanMastery,
                meanMastery == null ? "UNMEASURED" : bandOf(meanMastery),
                t == null ? 0 : t.attempts(),
                affectedLearners.size(), activeSignals,
                engagementCount, due,
                agg.servableByPrimaryTopic().getOrDefault(node.id(), 0));
    }

    private List<WeakPrerequisiteView> weakPrerequisites(UUID rootId, Scope scope,
                                                          Aggregates agg) {
        double weakCeiling = properties.weakMasteryCeiling();
        Map<UUID, List<KnowledgeGraphService.PrerequisiteRelation>> dependentsOf = new HashMap<>();
        for (KnowledgeGraphService.PrerequisiteRelation r : graph.prerequisiteRelations(rootId)) {
            dependentsOf.computeIfAbsent(r.prerequisiteId(), k -> new ArrayList<>()).add(r);
        }
        List<WeakPrerequisiteView> weak = new ArrayList<>();
        for (Map.Entry<UUID, List<KnowledgeGraphService.PrerequisiteRelation>> e
                : dependentsOf.entrySet()) {
            PerTopic t = agg.byTopic().get(e.getKey());
            NodeView node = scope.structureById().get(e.getKey());
            if (node == null || t == null || t.learnersMeasured() == 0
                    || t.meanMastery() == null || t.meanMastery() >= weakCeiling) {
                continue;   // unmeasured prerequisites are never claimed weak
            }
            List<DependentView> dependents = e.getValue().stream()
                    .map(r -> {
                        NodeView d = scope.structureById().get(r.dependentNodeId());
                        if (d == null) {
                            return null;
                        }
                        PerTopic dt = agg.byTopic().get(d.id());
                        return new DependentView(d.id(), d.code(), d.title(),
                                dt == null || dt.learnersMeasured() == 0
                                        ? null : dt.meanMastery());
                    })
                    .filter(d -> d != null)
                    .sorted(Comparator.comparing(DependentView::code)).toList();
            weak.add(new WeakPrerequisiteView(node.id(), node.code(), node.title(),
                    t.learnersMeasured(), t.meanMastery(), bandOf(t.meanMastery()),
                    dependents));
        }
        weak.sort(Comparator.comparing(WeakPrerequisiteView::meanMastery)
                .thenComparing(w -> w.dependents().size(), Comparator.reverseOrder())
                .thenComparing(WeakPrerequisiteView::prerequisiteCode));
        return weak.stream().limit(WEAK_PREREQUISITE_CAP).toList();
    }

    private ClassLearnerView learnerRow(User u, Scope scope, Aggregates agg,
                                        Map<UUID, List<SkillState>> skillsByLearner,
                                        Map<UUID, List<MisconceptionState>> miscoByLearner,
                                        Map<UUID, List<TutorTopicEngagement>> engagementByLearner,
                                        Map<UUID, Integer> dueByLearner) {
        List<SkillState> learnerSkills = skillsByLearner.getOrDefault(u.id(), List.of());
        List<MisconceptionState> learnerMisco = miscoByLearner.getOrDefault(u.id(), List.of());
        List<TutorTopicEngagement> learnerEngagements =
                engagementByLearner.getOrDefault(u.id(), List.of());

        Double meanMastery = learnerSkills.isEmpty() ? null
                : round(learnerSkills.stream().mapToDouble(SkillState::mastery)
                        .average().orElse(0));
        int attemptsTotal = learnerSkills.stream().mapToInt(SkillState::attempts).sum();

        // weakest measured topics (same evidence floor as NBA weakness advice)
        int minAttempts = properties.minAttemptsForWeakness();
        List<TopicMasteryView> weakest = learnerSkills.stream()
                .filter(s -> s.attempts() >= minAttempts)
                .sorted(Comparator.comparingDouble(SkillState::mastery))
                .limit(WEAKEST_TOPICS_PER_LEARNER)
                .map(s -> {
                    NodeView n = scope.structureById().get(s.nodeId());
                    return new TopicMasteryView(s.nodeId(),
                            n == null ? "?" : n.code(), n == null ? "?" : n.title(),
                            round(s.mastery()), bandOf(s.mastery()), s.attempts());
                })
                .toList();

        // active misconception signals, strongest first (BDT estimates)
        double threshold = learnerProperties.bdt().activeThreshold();
        List<MisconceptionSignalView> signals = learnerMisco.stream()
                .filter(m -> m.probability() >= threshold)
                .sorted(Comparator.comparingDouble(MisconceptionState::probability).reversed())
                .limit(MISCONCEPTION_SIGNALS_PER_LEARNER)
                .map(m -> {
                    NodeView n = scope.misconceptionById().get(m.misconceptionNodeId());
                    UUID parentTopic = scope.misconceptionParentOf().get(m.misconceptionNodeId());
                    NodeView p = parentTopic == null ? null : scope.structureById().get(parentTopic);
                    return new MisconceptionSignalView(m.misconceptionNodeId(),
                            n == null ? "?" : n.code(), n == null ? "?" : n.title(),
                            round(m.probability()), m.evidenceCount(), parentTopic,
                            p == null ? null : p.code());
                })
                .toList();

        Map<String, Integer> signalCounts = new LinkedHashMap<>();
        for (TutorTopicEngagement e : learnerEngagements) {
            signalCounts.merge(e.signalType(), 1, Integer::sum);
        }
        Instant lastEngagement = learnerEngagements.stream()
                .map(TutorTopicEngagement::occurredAt).max(Comparator.naturalOrder()).orElse(null);
        long[] recent = agg.recentByLearner().getOrDefault(u.id(), new long[]{0, 0});

        boolean measured = !learnerSkills.isEmpty() || !learnerMisco.isEmpty()
                || !learnerEngagements.isEmpty();
        return new ClassLearnerView(
                u.id(), u.displayName(), u.createdAt(),
                measured ? "MEASURED" : "UNMEASURED",
                learnerSkills.size(), meanMastery, attemptsTotal,
                (int) recent[0], (int) recent[1],
                agg.lastActivityByLearner().get(u.id()),
                weakest,
                signals.size(), signals,
                learnerEngagements.size(), signalCounts, lastEngagement,
                dueByLearner.getOrDefault(u.id(), 0));
    }

    private List<AffectedLearnerView> affectedLearners(NodeView topic, Scope scope,
                                                        Aggregates agg) {
        double weakCeiling = properties.weakMasteryCeiling();
        int minAttempts = properties.minAttemptsForWeakness();
        Map<UUID, Double> masteryOf = new LinkedHashMap<>();
        Map<UUID, List<MisconceptionSignalView>> signalsOf = new LinkedHashMap<>();
        PerTopic t = agg.byTopic().get(topic.id());
        if (t != null) {
            for (SkillState s : t.states()) {
                if (s.attempts() >= minAttempts && s.mastery() < weakCeiling) {
                    masteryOf.put(s.learnerId(), round(s.mastery()));
                }
            }
        }
        double threshold = learnerProperties.bdt().activeThreshold();
        for (NodeView misco : scope.misconceptionsOf().getOrDefault(topic.id(), List.of())) {
            for (MisconceptionState m : agg.misconceptions()) {
                if (m.misconceptionNodeId().equals(misco.id())
                        && m.probability() >= threshold) {
                    signalsOf.computeIfAbsent(m.learnerId(), k -> new ArrayList<>())
                            .add(new MisconceptionSignalView(
                                    m.misconceptionNodeId(), misco.code(), misco.title(),
                                    round(m.probability()), m.evidenceCount(),
                                    topic.id(), topic.code()));
                }
            }
        }
        Set<UUID> ids = new HashSet<>();
        ids.addAll(masteryOf.keySet());
        ids.addAll(signalsOf.keySet());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (User u : users.findAllById(ids)) {
            names.put(u.id(), u.displayName());
        }
        List<AffectedLearnerView> affected = new ArrayList<>();
        for (UUID id : ids) {
            boolean weak = masteryOf.containsKey(id);
            boolean misco = signalsOf.containsKey(id);
            String reason = weak && misco ? "LOW_MASTERY_AND_ACTIVE_MISCONCEPTION"
                    : weak ? "LOW_MASTERY" : "ACTIVE_MISCONCEPTION";
            affected.add(new AffectedLearnerView(id, names.getOrDefault(id, "unknown"),
                    masteryOf.get(id), reason,
                    List.copyOf(signalsOf.getOrDefault(id, List.of()))));
        }
        affected.sort(Comparator
                .comparing(AffectedLearnerView::reason)
                .thenComparing(a -> a.mastery() == null ? 1.0 : a.mastery())
                .thenComparing(a -> a.displayName().toLowerCase()));
        return affected;
    }

    private List<EvidenceItemView> representativeEvidence(UUID nodeId) {
        List<Attempt> recent = attempts.findRecentByTopicNode(
                nodeId, PageRequest.of(0, DRILL_DOWN_EVIDENCE_CAP));
        if (recent.isEmpty()) {
            return List.of();
        }
        Set<UUID> learnerIds = new HashSet<>();
        for (Attempt a : recent) {
            learnerIds.add(a.learnerId());
        }
        Map<UUID, String> names = new HashMap<>();
        for (User u : users.findAllById(learnerIds)) {
            names.put(u.id(), u.displayName());
        }
        return recent.stream()
                .map(a -> new EvidenceItemView(
                        a.id(), a.learnerId(), names.getOrDefault(a.learnerId(), "unknown"),
                        a.question().id(), a.question().externalRef(), a.correct(),
                        a.marksAwarded(), a.question().marks(),
                        a.markingState().name(), a.createdAt()))
                .toList();
    }

    /** the SAME banding the learner-facing mastery surfaces use */
    private String bandOf(double mastery) {
        return learnerProperties.decay().toParams().bandOf(mastery);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Instant i ? i
                : Instant.ofEpochMilli(((java.sql.Timestamp) o).getTime());
    }
}
