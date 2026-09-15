package com.syllabai.learner;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.dto.SmartLessonView;
import com.syllabai.learner.dto.SmartLessonView.ActionType;
import com.syllabai.learner.dto.SmartLessonView.EvidenceFactView;
import com.syllabai.learner.dto.SmartLessonView.MisconceptionStatusView;
import com.syllabai.learner.dto.SmartLessonView.PrerequisiteStatusView;
import com.syllabai.learner.dto.SmartLessonView.LessonActionView;
import com.syllabai.learner.dto.SmartLessonView.ReasonCode;
import com.syllabai.learner.dto.SmartLessonView.TopicStatusView;
import com.syllabai.recommendation.ConceptDependencyGraph;
import com.syllabai.recommendation.ConceptDependencyGraph.SemanticRelation;
import com.syllabai.recommendation.RecommendationProperties;
import com.syllabai.shared.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Smart Lesson (productization sprint §2; v2 = sprint-2 §8): the smallest
 * production-quality adaptive workflow on top of the EXISTING learner model —
 * no new state, no new thresholds, no LLM in the loop.
 *
 * <p>Decision ladder (deterministic, evidence-gated, mirrors the NBA tiers but
 * focused on ONE selected topic):</p>
 * <ol>
 *   <li>prerequisite gate — a DIRECT prerequisite measured weak redirects the
 *       lesson to the prerequisite (measured evidence only; the graph alone
 *       never acts, same philosophy as NBA T2/T2b);</li>
 *   <li>active misconception on the topic — validated REMEDIATED_BY edge ⇒
 *       study the corrective concept, otherwise ask the Tutor (NBA T4/T4b).
 *       v2: equal probabilities order by FRESHEST evidence, then code;</li>
 *   <li>review schedule due on the topic (NBA T1) — v2 names the most
 *       overdue schedule and how overdue it is;</li>
 *   <li>measured fluency gap (NBA T5);</li>
 *   <li>established weak mastery (NBA T6);</li>
 *   <li>tutor-engaged but never practised (NBA T7a) — v2 states the §9 signal
 *       mix and the derived multi-row signals (repeated explanation request,
 *       unresolved ask, post-explanation engagement) as evidence;</li>
 *   <li>no attempt evidence — start the topic (diagnostic practice);</li>
 *   <li>mastered — advance. v2 advance is evidence-aware:
 *       <ol type="a">
 *         <li>the unstarted topic with the MOST RECENT confusion-family signal
 *             jumps the queue (their own doubt, clarified requests and
 *             prerequisite-help asks outrank curriculum order);</li>
 *         <li>otherwise the most-overdue due review anywhere in the subject
 *             (review state is decay evidence — retrieval before new material);</li>
 *         <li>otherwise the first unstarted topic whose direct prerequisites
 *             are NOT measured-weak (prerequisite readiness);</li>
 *         <li>otherwise the first weak measured topic (consolidate gaps);</li>
 *         <li>if every unstarted topic is blocked by a measured-weak
 *             prerequisite, remediate the weakest blocking prerequisite
 *             instead of advancing into a wall;</li>
 *         <li>if every topic is measured strong, consolidate with a review on
 *             the STALEST measured topic (evidence freshness — the one decay
 *             puts most at risk), which may be a different topic than the
 *             selected one.</li>
 *       </ol></li>
 * </ol>
 *
 * <p>v2 repeated-exposure avoidance: the deterministic starter question is
 * the first servable question the learner has NOT attempted yet (one batched
 * attempted-ids query per response); when all have been attempted the first
 * is revisited and the action says so. Every branch records the learner-state
 * facts it consumed into the response's evidence trace. The loop closes by
 * construction: acting creates evidence, evidence changes the next decision.
 * A tutor engagement is evidence of engagement, never of mastery — no
 * conversation directly changes a mastery estimate.</p>
 */
@Service
public class SmartLessonService {

    private final KnowledgeGraphService graph;
    private final LearnerModelService learnerModel;
    private final ReviewScheduleRepository reviewSchedules;
    private final ConceptDependencyGraph conceptGraph;
    private final ServableQuestionService servableQuestions;
    private final TutorTopicEngagementRepository engagements;
    private final LearnerProperties learnerProperties;
    private final RecommendationProperties properties;
    private final com.syllabai.learner.decay.EbbinghausDecayService decay;
    private final AttemptRepository attempts;

    public SmartLessonService(KnowledgeGraphService graph,
                              LearnerModelService learnerModel,
                              ReviewScheduleRepository reviewSchedules,
                              ConceptDependencyGraph conceptGraph,
                              ServableQuestionService servableQuestions,
                              TutorTopicEngagementRepository engagements,
                              LearnerProperties learnerProperties,
                              RecommendationProperties properties,
                              com.syllabai.learner.decay.EbbinghausDecayService decay,
                              AttemptRepository attempts) {
        this.graph = graph;
        this.learnerModel = learnerModel;
        this.reviewSchedules = reviewSchedules;
        this.conceptGraph = conceptGraph;
        this.servableQuestions = servableQuestions;
        this.engagements = engagements;
        this.learnerProperties = learnerProperties;
        this.properties = properties;
        this.decay = decay;
        this.attempts = attempts;
    }

    /** an attached misconception whose BDT probability is at/above the active threshold */
    private record ActiveMisconception(NodeView node, MisconceptionState state) { }

    /** newest-first comparison over misconception evidence timestamps (nulls last) */
    private static Comparator<ActiveMisconception> freshness() {
        return (a, b) -> {
            Instant la = a.state().lastEvidenceAt();
            Instant lb = b.state().lastEvidenceAt();
            if (la == null && lb == null) {
                return 0;
            }
            if (la == null) {
                return 1;    // unknown age sorts last
            }
            if (lb == null) {
                return -1;
            }
            return lb.compareTo(la);   // newest first
        };
    }

    /**
     * Everything the ladder consumes for one request. The windowed engagement
     * rows, the pending review schedules and the prerequisite relations are
     * each fetched ONCE here — no rung re-queries (§11 posture).
     */
    private record Context(
            NodeView tree,
            NodeView topic,
            Map<UUID, NodeView> byId,
            Map<String, NodeView> byCode,
            Map<UUID, SkillState> skills,
            Map<UUID, MisconceptionState> misconceptions,
            Map<UUID, Double> effective,
            List<ReviewSchedule> pendingReviews,
            List<KnowledgeGraphService.PrerequisiteRelation> relations,
            List<TutorTopicEngagement> recentEngagements,
            Instant now) {
    }

    @Transactional(readOnly = true)
    public SmartLessonView lessonFor(UUID learnerId, UUID rootId, UUID topicNodeId) {
        Instant now = Instant.now();
        var decayParams = learnerProperties.decay().toParams();

        NodeView tree = graph.treeWithMisconceptions(rootId);

        // registries over the subject subtree (hard subject isolation — a topic
        // outside the root's PART_OF subtree is a 404, not a silent cross-subject hop)
        Map<UUID, NodeView> byId = new HashMap<>();
        Map<UUID, UUID> parentOf = new HashMap<>();
        Map<String, NodeView> byCode = new HashMap<>();
        collect(tree, null, byId, parentOf, byCode);

        NodeView topic = byId.get(topicNodeId);
        if (topic == null) {
            throw new NotFoundException("curriculum topic in this subject", topicNodeId);
        }

        // learner evidence, subject-scoped (same maps the NBA engine builds)
        Map<UUID, SkillState> skills = new HashMap<>();
        for (SkillState s : learnerModel.skillStates(learnerId)) {
            if (byId.containsKey(s.nodeId())) {
                skills.put(s.nodeId(), s);
            }
        }
        Map<UUID, MisconceptionState> misconceptions = new HashMap<>();
        for (MisconceptionState m : learnerModel.misconceptionStates(learnerId)) {
            if (byId.containsKey(m.misconceptionNodeId())) {
                misconceptions.put(m.misconceptionNodeId(), m);
            }
        }
        Map<UUID, Double> effective = new HashMap<>();
        for (Map.Entry<UUID, SkillState> e : skills.entrySet()) {
            SkillState s = e.getValue();
            effective.put(s.nodeId(), decayServiceValue(s, now, decayParams));
        }

        // one fetch per evidence source for the WHOLE response (v2 §8/§11)
        List<ReviewSchedule> pendingReviews = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING);
        List<KnowledgeGraphService.PrerequisiteRelation> relations =
                graph.prerequisiteRelations(tree.id());
        Instant tutorWindow = now.minus(Duration.ofDays(properties.tutorEngagementWindowDays()));
        List<TutorTopicEngagement> recentEngagements = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, tutorWindow);

        Context ctx = new Context(tree, topic, byId, byCode, skills, misconceptions,
                effective, pendingReviews, relations, recentEngagements, now);

        List<EvidenceFactView> evidence = new ArrayList<>();
        SkillState skill = skills.get(topicNodeId);
        TopicStatusView status = topicStatus(ctx, skill);
        evidence.add(new EvidenceFactView("topic", topic.code() + " — " + topic.title()));
        if (skill != null) {
            evidence.add(new EvidenceFactView("attempts", String.valueOf(skill.attempts())));
            evidence.add(new EvidenceFactView("mastery (raw)", fmt(skill.mastery())));
            evidence.add(new EvidenceFactView("mastery (decay-adjusted)",
                    fmt(effective.get(topicNodeId))));
        } else {
            evidence.add(new EvidenceFactView("attempts", "0 (no attempt evidence on this topic)"));
        }

        // KG learner-surface panels (§4): direct prerequisites with the
        // learner's mastery as an overlay, and attached misconceptions with
        // BDT probability + validated remediation targets. Measured facts and
        // validated relations only — mastery never touches curriculum nodes.
        List<PrerequisiteStatusView> prereqPanel = prerequisitePanel(ctx);
        List<MisconceptionStatusView> misconceptionPanel = misconceptionPanel(topic, byCode,
                misconceptions);

        LessonActionView action = decide(learnerId, ctx, evidence);

        return new SmartLessonView(learnerId, rootId, topicNodeId, topic.code(), topic.title(),
                now, SmartLessonView.POLICY_ID, action, status, prereqPanel, misconceptionPanel,
                List.copyOf(evidence));
    }

    /** prerequisite panel: one row per DIRECT prerequisite, mastery overlaid */
    private List<PrerequisiteStatusView> prerequisitePanel(Context ctx) {
        Set<UUID> direct = directPrerequisites(ctx.topic(), ctx.relations(), ctx.byId(),
                ctx.byCode());
        List<PrerequisiteStatusView> rows = new ArrayList<>();
        for (UUID prereqId : direct) {
            NodeView node = ctx.byId().get(prereqId);
            if (node == null) {
                continue;
            }
            SkillState s = ctx.skills().get(prereqId);
            Double eff = s == null ? null : ctx.effective().get(prereqId);
            rows.add(new PrerequisiteStatusView(node.id(), node.code(), node.title(),
                    eff, s == null ? null : s.attempts(),
                    eff != null && s.attempts() >= 1 && eff < properties.weakMasteryCeiling()));
        }
        rows.sort(Comparator.comparing(PrerequisiteStatusView::code,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** misconception panel: rows for every attached misconception + remediation target */
    private List<MisconceptionStatusView> misconceptionPanel(NodeView topic,
                                                             Map<String, NodeView> byCode,
                                                             Map<UUID, MisconceptionState> misconceptions) {
        List<MisconceptionStatusView> rows = new ArrayList<>();
        if (topic.children() == null) {
            return rows;
        }
        for (NodeView child : topic.children()) {
            if (!"MISCONCEPTION".equals(child.type())) {
                continue;
            }
            MisconceptionState m = misconceptions.get(child.id());
            String remediation = null;
            for (ConceptDependencyGraph.Edge edge : conceptGraph.edges(
                    SemanticRelation.REMEDIATED_BY)) {
                if (child.code() != null && child.code().equals(edge.source())) {
                    NodeView corrective = byCode.get(edge.target());
                    if (corrective != null) {
                        remediation = corrective.code();
                        break;
                    }
                }
            }
            rows.add(new MisconceptionStatusView(child.id(), child.code(), child.title(),
                    m == null ? null : m.probability(),
                    m != null && m.probability() >= learnerProperties.bdt().activeThreshold(),
                    remediation));
        }
        rows.sort(Comparator.comparing(MisconceptionStatusView::code,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** direct prerequisites of a topic: DB relations + validated chain edges, subtree-filtered */
    private Set<UUID> directPrerequisites(NodeView topic,
                                          List<KnowledgeGraphService.PrerequisiteRelation> relations,
                                          Map<UUID, NodeView> byId, Map<String, NodeView> byCode) {
        Set<UUID> direct = new HashSet<>();
        for (KnowledgeGraphService.PrerequisiteRelation rel : relations) {
            if (topic.id().equals(rel.dependentNodeId()) && byId.containsKey(rel.prerequisiteId())) {
                direct.add(rel.prerequisiteId());
            }
        }
        for (ConceptDependencyGraph.Edge edge : conceptGraph.edges(
                SemanticRelation.REQUIRES_PREREQUISITE)) {
            if (topic.code() != null && topic.code().equals(edge.source())) {
                NodeView prereq = byCode.get(edge.target());
                if (prereq != null) {
                    direct.add(prereq.id());
                }
            }
        }
        return direct;
    }

    // ── the ladder ──────────────────────────────────────────────────────

    private LessonActionView decide(UUID learnerId, Context ctx, List<EvidenceFactView> evidence) {
        NodeView topic = ctx.topic();

        // (1) prerequisite gate — direct prerequisites of the selected topic
        Set<UUID> directPrereqs = directPrerequisites(topic, ctx.relations(), ctx.byId(),
                ctx.byCode());
        record WeakPrereq(NodeView node, double eff, int attempts) { }
        List<WeakPrereq> weakPrereqs = new ArrayList<>();
        for (UUID prereqId : directPrereqs) {
            NodeView node = ctx.byId().get(prereqId);
            if (node == null) {
                continue;   // outside this subject's subtree — isolation wins
            }
            SkillState s = ctx.skills().get(prereqId);
            if (s == null) {
                evidence.add(new EvidenceFactView("prerequisite " + node.code(),
                        "not yet measured — not a blocker"));
                continue;
            }
            Double eff = ctx.effective().get(prereqId);
            if (eff != null && s.attempts() >= 1 && eff < properties.weakMasteryCeiling()) {
                weakPrereqs.add(new WeakPrereq(node, eff, s.attempts()));
            }
        }
        if (!weakPrereqs.isEmpty()) {
            weakPrereqs.sort(Comparator
                    .comparingDouble(WeakPrereq::eff)
                    .thenComparing(wp -> wp.node().code()));
            WeakPrereq weakest = weakPrereqs.get(0);
            for (WeakPrereq wp : weakPrereqs) {
                evidence.add(new EvidenceFactView("prerequisite " + wp.node().code(),
                        "measured " + fmt(wp.eff()) + " over " + wp.attempts() + " attempt(s)"));
            }
            return practiceAction(learnerId, ActionType.REMEDIATE_PREREQUISITE,
                    ReasonCode.PREREQUISITE_WEAK, weakest.node(),
                    "Prerequisite " + weakest.node().code() + " (" + weakest.node().title()
                            + ") is measured weak (" + fmt(weakest.eff()) + " over "
                            + weakest.attempts() + " attempts) — strengthen the foundation"
                            + " before " + topic.code());
        }

        // (2) active misconception attached to this topic (children of type
        // MISCONCEPTION in the folded tree). v2: probability desc, then
        // FRESHEST evidence first, then code — all deterministic.
        List<ActiveMisconception> activeMisconceptions = new ArrayList<>();
        if (topic.children() != null) {
            for (NodeView child : topic.children()) {
                if (!"MISCONCEPTION".equals(child.type())) {
                    continue;
                }
                MisconceptionState m = ctx.misconceptions().get(child.id());
                if (m == null || m.probability() < learnerProperties.bdt().activeThreshold()) {
                    continue;
                }
                activeMisconceptions.add(new ActiveMisconception(child, m));
            }
        }
        activeMisconceptions.sort(Comparator
                .comparingDouble((ActiveMisconception a) -> a.state().probability()).reversed()
                .thenComparing(freshness())
                .thenComparing(a -> a.node().code()));
        if (!activeMisconceptions.isEmpty()) {
            ActiveMisconception strongest = activeMisconceptions.get(0);
            MisconceptionState strongestState = strongest.state();
            NodeView strongestMisconception = strongest.node();
            evidence.add(new EvidenceFactView("misconception " + strongestMisconception.code(),
                    "probability " + fmt(strongestState.probability()) + " from "
                            + strongestState.evidenceCount() + " evidence item(s), last evidence "
                            + strongestState.lastEvidenceAt()));
            // validated corrective concept?
            for (ConceptDependencyGraph.Edge edge : conceptGraph.edges(SemanticRelation.REMEDIATED_BY)) {
                if (strongestMisconception.code() != null
                        && strongestMisconception.code().equals(edge.source())) {
                    NodeView corrective = ctx.byCode().get(edge.target());
                    if (corrective != null) {
                        return practiceAction(learnerId, ActionType.STUDY_CORRECTIVE,
                                ReasonCode.MISCONCEPTION_REMEDIATION, corrective,
                                "Misconception \"" + strongestMisconception.title()
                                        + "\" (probability " + fmt(strongestState.probability())
                                        + ", last evidence " + strongestState.lastEvidenceAt()
                                        + ") — validated remediation: study "
                                        + corrective.code() + " (" + corrective.title()
                                        + "), then ask the Tutor for the corrective explanation");
                    }
                }
            }
            return new LessonActionView(ActionType.ASK_TUTOR, ReasonCode.MISCONCEPTION_SUSPECTED,
                    strongestMisconception.id(), strongestMisconception.code(),
                    strongestMisconception.title(), null, 0,
                    "Misconception \"" + strongestMisconception.title() + "\" is active "
                            + "(probability " + fmt(strongestState.probability()) + " from "
                            + strongestState.evidenceCount() + " evidence items, last evidence "
                            + strongestState.lastEvidenceAt() + ") — ask the Tutor"
                            + " for a grounded explanation before practising");
        }

        // (3) review due on the topic — v2: the MOST OVERDUE pending schedule,
        // with the due date stated (evidence freshness)
        SkillState skill = ctx.skills().get(topic.id());
        ReviewSchedule due = ctx.pendingReviews().stream()
                .filter(r -> topic.id().equals(r.nodeId()))
                .min(Comparator.comparing(ReviewSchedule::dueAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        if (due != null && skill != null) {
            evidence.add(new EvidenceFactView("review schedule",
                    "retrieval practice due " + due.dueAt() + " ("
                            + overdueNote(due.dueAt(), ctx.now()) + ", decay-triggered)"));
            return practiceAction(learnerId, ActionType.REVIEW_TOPIC, ReasonCode.DUE_REVIEW,
                    topic,
                    "Retrieval practice is due on " + topic.code() + " (scheduled "
                            + due.dueAt() + ", " + overdueNote(due.dueAt(), ctx.now())
                            + ") — last practised "
                            + (skill.lastPracticedAt() == null ? "never in this window"
                            : skill.lastPracticedAt().toString())
                            + ", decay-adjusted mastery " + fmt(ctx.effective().get(topic.id())));
        }

        // (4) fluency gap
        if (skill != null && skill.proceduralFluencyGap() != null
                && skill.proceduralFluencyGap() >= properties.fluencyGapThreshold()) {
            evidence.add(new EvidenceFactView("fluency gap (untimed − timed accuracy)",
                    fmt(skill.proceduralFluencyGap()) + " over " + skill.attempts() + " attempts"));
            return practiceAction(learnerId, ActionType.TIMED_PRACTICE, ReasonCode.FLUENCY_GAP,
                    topic,
                    "Untimed-vs-timed accuracy gap " + fmt(skill.proceduralFluencyGap())
                            + " over " + skill.attempts() + " attempts — practise under timed"
                            + " conditions");
        }

        // (5) established weak mastery
        if (skill != null && skill.attempts() >= properties.minAttemptsForWeakness()) {
            Double eff = ctx.effective().get(topic.id());
            if (eff != null && eff < properties.weakMasteryCeiling()) {
                return practiceAction(learnerId, ActionType.PRACTISE_QUESTIONS,
                        ReasonCode.LOW_MASTERY, topic,
                        "Measured mastery " + fmt(eff) + " over " + skill.attempts()
                                + " attempts is below the weak ceiling ("
                                + fmt(properties.weakMasteryCeiling()) + ") — keep practising "
                                + topic.code());
            }
        }

        // (6) tutor-engaged but never practised (NBA T7a), with the V23/V25
        // structured signals: the mix says WHAT the engagement was, and the
        // §9 derived multi-row signals are stated as evidence — none of them
        // is mastery.
        List<TutorTopicEngagement> topicAsks = ctx.recentEngagements().stream()
                .filter(e -> topic.id().equals(e.nodeId())).toList();
        long asks = topicAsks.size();
        if (asks > 0 && skill == null) {
            Map<String, Long> signalCounts = TutorSignalPolicy.signalCounts(topicAsks);
            evidence.add(new EvidenceFactView("tutor engagement",
                    asks + " ask(s) in the last " + properties.tutorEngagementWindowDays()
                            + " day(s), no attempt evidence yet"));
            evidence.add(new EvidenceFactView("engagement signals", signalCounts.toString()));
            List<String> derived = new ArrayList<>();
            if (TutorSignalPolicy.repeatedExplanationRequest(topicAsks)) {
                derived.add("repeated explanation requests");
            }
            if (TutorSignalPolicy.unresolvedQuestion(topicAsks)) {
                derived.add("an unresolved ask (no grounded answer)");
            }
            if (TutorSignalPolicy.postExplanationEngagement(topicAsks)) {
                derived.add("engagement after an explanation");
            }
            if (!derived.isEmpty()) {
                evidence.add(new EvidenceFactView("derived signals",
                        String.join("; ", derived)));
            }
            String signalNote = signalCounts.containsKey("DOUBT_SIGNAL")
                    ? " — including confusion you reported yourself"
                    : signalCounts.containsKey("MISCONCEPTION_RELATED")
                    ? " — including a question linked to an active misconception"
                    : signalCounts.containsKey("PREREQUISITE_HELP")
                    ? " — including prerequisite help the Tutor policy selected"
                    : "";
            String derivedNote = derived.isEmpty() ? ""
                    : " (" + String.join("; ", derived) + ")";
            return practiceAction(learnerId, ActionType.PRACTISE_QUESTIONS,
                    ReasonCode.TUTOR_ENGAGED, topic,
                    "You asked the Tutor " + asks + " time(s) about " + topic.code() + " but have "
                            + "no attempt evidence yet" + signalNote + derivedNote
                            + " — convert the curiosity into practice");
        }

        // (7) no attempt evidence — start the topic
        if (skill == null) {
            return practiceAction(learnerId, ActionType.PRACTISE_QUESTIONS,
                    ReasonCode.INSUFFICIENT_COVERAGE, topic,
                    "No attempt evidence on " + topic.code() + " yet — start with the "
                            + "first validated question to find your level (diagnostic practice)");
        }

        // (8) mastered — advance (evidence-aware, deterministic)
        return advance(learnerId, ctx, evidence);
    }

    /**
     * v2 evidence-aware advance: most-recent confusion first, then the most
     * overdue due review, then prerequisite-ready unstarted topics, then weak
     * measured topics, then (when every unstarted topic is blocked) remediation
     * of the weakest blocking prerequisite, else honest consolidation on the
     * stalest measured topic.
     */
    private LessonActionView advance(UUID learnerId, Context ctx,
                                     List<EvidenceFactView> evidence) {
        NodeView topic = ctx.topic();

        // practicable candidates follow the NBA T7 convention: TOPIC/SUBTOPIC
        // nodes (never SUBJECT/UNIT/MISCONCEPTION) with servable questions.
        // Counts come from ONE batched activeWithin query (per-node queries
        // would reintroduce the N+1 shape §8 of the perf directive bans).
        Map<UUID, Long> servableCounts = new HashMap<>();
        for (StudentQuestionView q : servableQuestions.activeWithin(ctx.byId().keySet())) {
            if (q.primaryTopicNodeId() != null) {
                servableCounts.merge(q.primaryTopicNodeId(), 1L, Long::sum);
            }
        }
        List<NodeView> order = curriculumOrder(ctx.tree());
        Map<UUID, Instant> lastConfusionAt = TutorSignalPolicy
                .lastConfusionAtByTopic(ctx.recentEngagements());

        // pass 1 (§3/§8): an unstarted topic the learner reported confusion
        // about jumps the queue — the MOST RECENT confusion signal first
        // (recency), curriculum order breaking exact ties.
        if (!lastConfusionAt.isEmpty()) {
            NodeView best = null;
            Instant bestAt = null;
            for (NodeView node : order) {
                if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                    continue;
                }
                if (node.id().equals(topic.id()) || !lastConfusionAt.containsKey(node.id())) {
                    continue;
                }
                if (servableCounts.getOrDefault(node.id(), 0L) <= 0) {
                    continue;
                }
                if (ctx.skills().containsKey(node.id())) {
                    continue;   // confusion pass targets unstarted topics only
                }
                Instant at = lastConfusionAt.get(node.id());
                if (best == null || at.isAfter(bestAt)) {
                    best = node;
                    bestAt = at;
                }
            }
            if (best != null) {
                evidence.add(new EvidenceFactView("mastered " + topic.code(),
                        fmt(ctx.effective().get(topic.id())) + " over "
                                + ctx.skills().get(topic.id()).attempts() + " attempts"));
                evidence.add(new EvidenceFactView("confusion signal",
                        best.code() + " — most recent confusion-family engagement " + bestAt));
                return practiceAction(learnerId, ActionType.ADVANCE_TOPIC,
                        ReasonCode.TOPIC_MASTERED, best,
                        topic.code() + " is mastered (decay-adjusted mastery "
                                + fmt(ctx.effective().get(topic.id())) + ") — move on to "
                                + best.code() + " (" + best.title()
                                + "): you reported confusion here most recently ("
                                + bestAt + ")");
            }
        }

        // pass 1.5 (§8 review state): the most overdue pending review anywhere
        // in the subject with servable work — decay evidence outranks new
        // material. dueAt asc, curriculum order breaking exact ties.
        ReviewSchedule mostOverdue = null;
        NodeView reviewTarget = null;
        for (NodeView node : order) {
            if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                continue;
            }
            if (node.id().equals(topic.id())
                    || servableCounts.getOrDefault(node.id(), 0L) <= 0) {
                continue;
            }
            ReviewSchedule due = ctx.pendingReviews().stream()
                    .filter(r -> node.id().equals(r.nodeId()))
                    .min(Comparator.comparing(ReviewSchedule::dueAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(null);
            if (due == null) {
                continue;
            }
            if (mostOverdue == null || due.dueAt().isBefore(mostOverdue.dueAt())) {
                mostOverdue = due;
                reviewTarget = node;
            }
        }
        if (mostOverdue != null) {
            evidence.add(new EvidenceFactView("mastered " + topic.code(),
                    fmt(ctx.effective().get(topic.id())) + " over "
                            + ctx.skills().get(topic.id()).attempts() + " attempts"));
            evidence.add(new EvidenceFactView("due review",
                    reviewTarget.code() + " — scheduled " + mostOverdue.dueAt()
                            + " (" + overdueNote(mostOverdue.dueAt(), ctx.now()) + ")"));
            return practiceAction(learnerId, ActionType.REVIEW_TOPIC, ReasonCode.DUE_REVIEW,
                    reviewTarget,
                    topic.code() + " is mastered, and a review of " + reviewTarget.code() + " ("
                            + reviewTarget.title() + ") is due — scheduled "
                            + mostOverdue.dueAt() + " (" + overdueNote(mostOverdue.dueAt(),
                            ctx.now()) + "). Retrieval practice before new material");
        }

        // pass 2 (a): first UNSTARTED topic in curriculum order whose direct
        // prerequisites are not measured-weak (prerequisite readiness). An
        // unmeasured prerequisite is not a blocker (honest gap, not evidence
        // of weakness). (b): first unstarted topic regardless — but if EVERY
        // unstarted topic is blocked by a measured-weak prerequisite, the
        // evidence-aware action is remediating the weakest blocker instead.
        NodeView ready = null;
        NodeView blocked = null;
        for (NodeView node : order) {
            if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                continue;
            }
            if (node.id().equals(topic.id())) {
                continue;
            }
            if (servableCounts.getOrDefault(node.id(), 0L) <= 0) {
                continue;   // nothing validated to practise — not an advance target
            }
            SkillState s = ctx.skills().get(node.id());
            if (s != null) {
                Double eff = ctx.effective().get(node.id());
                if (eff != null && eff < properties.weakMasteryCeiling()) {
                    // first weak topic in curriculum order (standing rule)
                    if (ready == null) {
                        evidence.add(new EvidenceFactView("mastered " + topic.code(),
                                fmt(ctx.effective().get(topic.id())) + " over "
                                        + ctx.skills().get(topic.id()).attempts()
                                        + " attempts"));
                        evidence.add(new EvidenceFactView("next in curriculum order",
                                node.code() + " — measured " + fmt(eff) + " (weak)"));
                        return practiceAction(learnerId, ActionType.ADVANCE_TOPIC,
                                ReasonCode.TOPIC_MASTERED, node,
                                topic.code() + " is mastered (decay-adjusted mastery "
                                        + fmt(ctx.effective().get(topic.id())) + ") — move on to "
                                        + node.code() + " (" + node.title() + "): measured "
                                        + fmt(eff) + " (weak)");
                    }
                }
                continue;
            }
            // unstarted candidate — check prerequisite readiness
            NodeView blocker = measuredWeakPrerequisite(node, ctx);
            if (blocker == null) {
                ready = node;
                break;   // first prerequisite-ready unstarted topic in curriculum order
            }
            if (blocked == null) {
                blocked = node;
            }
        }
        if (ready != null) {
            evidence.add(new EvidenceFactView("mastered " + topic.code(),
                    fmt(ctx.effective().get(topic.id())) + " over "
                            + ctx.skills().get(topic.id()).attempts() + " attempts"));
            evidence.add(new EvidenceFactView("next in curriculum order",
                    ready.code() + " — not yet started, direct prerequisites not weak"));
            return practiceAction(learnerId, ActionType.ADVANCE_TOPIC, ReasonCode.TOPIC_MASTERED,
                    ready,
                    topic.code() + " is mastered (decay-adjusted mastery "
                            + fmt(ctx.effective().get(topic.id())) + ") — move on to "
                            + ready.code() + " (" + ready.title() + "): not yet started");
        }

        // every unstarted topic is blocked by a measured-weak prerequisite:
        // remediate the weakest blocker (deterministic: effective asc, code)
        if (blocked != null) {
            record Blocker(NodeView node, double eff, int attempts) { }
            List<Blocker> blockers = new ArrayList<>();
            for (NodeView node : order) {
                if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                    continue;
                }
                if (node.id().equals(topic.id())
                        || servableCounts.getOrDefault(node.id(), 0L) <= 0
                        || ctx.skills().containsKey(node.id())) {
                    continue;
                }
                NodeView blocker = measuredWeakPrerequisite(node, ctx);
                if (blocker != null) {
                    SkillState bs = ctx.skills().get(blocker.id());
                    Double eff = ctx.effective().get(blocker.id());
                    if (bs != null && eff != null) {
                        blockers.add(new Blocker(blocker, eff, bs.attempts()));
                    }
                }
            }
            if (!blockers.isEmpty()) {
                blockers.sort(Comparator.comparingDouble(Blocker::eff)
                        .thenComparing(b -> b.node().code()));
                Blocker weakest = blockers.get(0);
                evidence.add(new EvidenceFactView("mastered " + topic.code(),
                        fmt(ctx.effective().get(topic.id())) + " over "
                                + ctx.skills().get(topic.id()).attempts() + " attempts"));
                evidence.add(new EvidenceFactView("advance blocked",
                        "every unstarted topic has a measured-weak prerequisite; weakest is "
                                + weakest.node().code() + " (" + fmt(weakest.eff()) + ")"));
                return practiceAction(learnerId, ActionType.REMEDIATE_PREREQUISITE,
                        ReasonCode.PREREQUISITE_WEAK, weakest.node(),
                        topic.code() + " is mastered, but every unstarted topic is blocked by a "
                                + "measured-weak prerequisite — strengthen " + weakest.node().code()
                                + " (" + weakest.node().title() + ", measured " + fmt(weakest.eff())
                                + " over " + weakest.attempts() + " attempts) first");
            }
            // no measurable blocker (should not happen — defensive honest fallthrough)
            evidence.add(new EvidenceFactView("advance blocked",
                    blocked.code() + " is unstarted with weak prerequisite evidence"));
            return practiceAction(learnerId, ActionType.ADVANCE_TOPIC, ReasonCode.TOPIC_MASTERED,
                    blocked,
                    topic.code() + " is mastered — move on to " + blocked.code() + " ("
                            + blocked.title() + "): not yet started");
        }

        // consolidation: every topic is measured strong — review the STALEST
        // measured topic (evidence freshness: the one decay puts most at
        // risk), which may be the selected topic itself. lastPracticedAt
        // oldest first; exact ties prefer the selected topic, then curriculum
        // order. Servable work preferred but not required for a review round.
        NodeView stalest = null;
        Instant stalestAt = null;
        for (NodeView node : order) {
            if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                continue;
            }
            SkillState s = ctx.skills().get(node.id());
            if (s == null || s.lastPracticedAt() == null) {
                continue;
            }
            if (servableCounts.getOrDefault(node.id(), 0L) <= 0
                    && !node.id().equals(topic.id())) {
                continue;   // no servable work elsewhere — not a review target
            }
            if (stalest == null || s.lastPracticedAt().isBefore(stalestAt)) {
                stalest = node;
                stalestAt = s.lastPracticedAt();
            }
        }
        evidence.add(new EvidenceFactView("curriculum scan",
                "every topic in this subject is measured strong or has no servable work left"));
        if (stalest != null && !stalest.id().equals(topic.id())) {
            evidence.add(new EvidenceFactView("stalest measured topic",
                    stalest.code() + " — last practised " + stalestAt));
            return practiceAction(learnerId, ActionType.REVIEW_TOPIC, ReasonCode.TOPIC_MASTERED,
                    stalest,
                    topic.code() + " is mastered and every curriculum topic is measured strong — "
                            + "consolidate with a review round on " + stalest.code() + " ("
                            + stalest.title() + "), your stalest measured topic (last practised "
                            + stalestAt + ")");
        }
        return practiceAction(learnerId, ActionType.REVIEW_TOPIC, ReasonCode.TOPIC_MASTERED,
                topic,
                topic.code() + " is mastered and every curriculum topic is measured strong — "
                        + "consolidate with a review round here, or pick a new subject area");
    }

    /**
     * The measured-weak direct prerequisite of a candidate topic, or null when
     * every direct prerequisite is unmeasured or measured strong (in-memory
     * over the hoisted relations — no queries).
     */
    private NodeView measuredWeakPrerequisite(NodeView candidate, Context ctx) {
        for (UUID prereqId : directPrerequisites(candidate, ctx.relations(), ctx.byId(),
                ctx.byCode())) {
            NodeView node = ctx.byId().get(prereqId);
            if (node == null) {
                continue;
            }
            SkillState s = ctx.skills().get(prereqId);
            if (s == null) {
                continue;   // unmeasured — an honest gap, not a blocker
            }
            Double eff = ctx.effective().get(prereqId);
            if (eff != null && s.attempts() >= 1 && eff < properties.weakMasteryCeiling()) {
                return node;
            }
        }
        return null;
    }

    /**
     * Attach the deterministic starter question. v2 repeated-exposure
     * avoidance: the FIRST servable question the learner has not attempted
     * yet (one batched attempted-ids query over this topic's candidates);
     * when all have been attempted, revisit the first and say so.
     */
    private LessonActionView practiceAction(UUID learnerId, ActionType type, ReasonCode reason,
                                            NodeView target, String detail) {
        List<StudentQuestionView> servable = servableQuestions.activeByTopic(target.id());
        UUID questionId = null;
        String rotationNote = "";
        if (!servable.isEmpty()) {
            Set<UUID> attempted = new HashSet<>(attempts.findAttemptedQuestionIds(learnerId,
                    servable.stream().map(StudentQuestionView::id).toList()));
            StudentQuestionView chosen = null;
            for (StudentQuestionView q : servable) {
                if (!attempted.contains(q.id())) {
                    chosen = q;
                    break;
                }
            }
            long attemptedServable = servable.stream()
                    .filter(q -> attempted.contains(q.id())).count();
            if (chosen == null) {
                chosen = servable.get(0);   // all attempted — revisit the first, honestly
                rotationNote = " (all " + servable.size()
                        + " validated question(s) attempted — revisiting the first)";
            } else if (attemptedServable > 0) {
                rotationNote = " (starter question not attempted yet; " + attemptedServable
                        + " of " + servable.size() + " already attempted)";
            }
            questionId = chosen.id();
        }
        return new LessonActionView(type, reason, target.id(), target.code(), target.title(),
                questionId, servable.size(), detail + rotationNote);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private TopicStatusView topicStatus(Context ctx, SkillState skill) {
        NodeView topic = ctx.topic();
        int attempts = skill == null ? 0 : skill.attempts();
        String coverage = skill == null ? "UNMEASURED"
                : attempts < properties.minAttemptsForWeakness() ? "PARTIAL" : "ESTABLISHED";
        boolean reviewDue = ctx.pendingReviews().stream()
                .anyMatch(r -> topic.id().equals(r.nodeId()));
        Double misProb = null;
        if (topic.children() != null) {
            for (NodeView child : topic.children()) {
                if ("MISCONCEPTION".equals(child.type())) {
                    MisconceptionState m = ctx.misconceptions().get(child.id());
                    if (m != null && (misProb == null || m.probability() > misProb)) {
                        misProb = m.probability();
                    }
                }
            }
        }
        long asks = ctx.recentEngagements().stream()
                .filter(e -> topic.id().equals(e.nodeId())).count();
        return new TopicStatusView(coverage, attempts,
                skill == null ? null : skill.mastery(),
                skill == null ? null : ctx.effective().get(topic.id()),
                reviewDue, misProb,
                skill == null ? null : skill.proceduralFluencyGap(),
                asks, servableQuestions.activeByTopic(topic.id()).size());
    }

    /** human-readable overdue/due-in note for a review due date */
    private static String overdueNote(Instant dueAt, Instant now) {
        if (dueAt == null) {
            return "no due date recorded";
        }
        Duration d = Duration.between(dueAt, now);
        if (d.isZero()) {
            return "due now";
        }
        if (d.isNegative()) {
            return "due in " + humanize(d.negated());
        }
        return "overdue by " + humanize(d);
    }

    private static String humanize(Duration d) {
        long days = d.toDays();
        if (days >= 1) {
            return days + " day(s)";
        }
        long hours = d.toHours();
        if (hours >= 1) {
            return hours + " hour(s)";
        }
        return d.toMinutes() + " minute(s)";
    }

    private double decayServiceValue(SkillState s, Instant now,
                                     com.syllabai.learner.decay.DecayParams decayParams) {
        return decay.decayed(s.mastery(), s.lastPracticedAt(), now, decayParams);
    }

    private static void collect(NodeView node, NodeView parent,
                                Map<UUID, NodeView> byId, Map<UUID, UUID> parentOf,
                                Map<String, NodeView> byCode) {
        byId.put(node.id(), node);
        parentOf.put(node.id(), parent == null ? null : parent.id());
        if (node.code() != null && !node.code().isBlank()) {
            byCode.putIfAbsent(node.code(), node);
        }
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                collect(child, node, byId, parentOf, byCode);
            }
        }
    }

    private static List<NodeView> curriculumOrder(NodeView root) {
        List<NodeView> out = new ArrayList<>();
        walkOrder(root, out);
        return out;
    }

    private static void walkOrder(NodeView node, List<NodeView> out) {
        out.add(node);
        if (node.children() != null) {
            for (NodeView child : node.children()) {
                walkOrder(child, out);
            }
        }
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
