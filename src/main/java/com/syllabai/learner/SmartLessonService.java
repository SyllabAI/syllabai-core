package com.syllabai.learner;

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
 * Smart Lesson MVP (productization sprint §2): the smallest production-quality
 * adaptive workflow on top of the EXISTING learner model — no new state, no new
 * thresholds, no LLM in the loop.
 *
 * <p>Decision ladder (deterministic, evidence-gated, mirrors the NBA tiers but
 * focused on ONE selected topic):</p>
 * <ol>
 *   <li>prerequisite gate — a DIRECT prerequisite measured weak redirects the
 *       lesson to the prerequisite (measured evidence only; the graph alone
 *       never acts, same philosophy as NBA T2/T2b);</li>
 *   <li>active misconception on the topic — validated REMEDIATED_BY edge ⇒
 *       study the corrective concept, otherwise ask the Tutor (NBA T4/T4b);</li>
 *   <li>review schedule due on the topic (NBA T1);</li>
 *   <li>measured fluency gap (NBA T5);</li>
 *   <li>established weak mastery (NBA T6);</li>
 *   <li>tutor-engaged but never practised (NBA T7a);</li>
 *   <li>no attempt evidence — start the topic (diagnostic practice);</li>
 *   <li>mastered — advance to the next topic in curriculum order that still
 *       has work to do; if none remains, say so honestly.</li>
 * </ol>
 *
 * <p>Every branch records the learner-state facts it consumed into the
 * response's evidence trace, and the action carries a deterministic starter
 * question whenever practice is involved. The loop closes by construction:
 * acting creates evidence, evidence changes the next decision.</p>
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

    public SmartLessonService(KnowledgeGraphService graph,
                              LearnerModelService learnerModel,
                              ReviewScheduleRepository reviewSchedules,
                              ConceptDependencyGraph conceptGraph,
                              ServableQuestionService servableQuestions,
                              TutorTopicEngagementRepository engagements,
                              LearnerProperties learnerProperties,
                              RecommendationProperties properties,
                              com.syllabai.learner.decay.EbbinghausDecayService decay) {
        this.graph = graph;
        this.learnerModel = learnerModel;
        this.reviewSchedules = reviewSchedules;
        this.conceptGraph = conceptGraph;
        this.servableQuestions = servableQuestions;
        this.engagements = engagements;
        this.learnerProperties = learnerProperties;
        this.properties = properties;
        this.decay = decay;
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

        List<EvidenceFactView> evidence = new ArrayList<>();
        SkillState skill = skills.get(topicNodeId);
        TopicStatusView status = topicStatus(learnerId, topic, skill, effective,
                misconceptions, now);
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
        List<PrerequisiteStatusView> prereqPanel = prerequisitePanel(topic, tree, byId, byCode,
                skills, effective);
        List<MisconceptionStatusView> misconceptionPanel = misconceptionPanel(topic, byCode,
                misconceptions);

        LessonActionView action = decide(learnerId, tree, topic, byId, byCode, skills,
                misconceptions, effective, evidence, now);

        return new SmartLessonView(learnerId, rootId, topicNodeId, topic.code(), topic.title(),
                now, SmartLessonView.POLICY_ID, action, status, prereqPanel, misconceptionPanel,
                List.copyOf(evidence));
    }

    /** prerequisite panel: one row per DIRECT prerequisite, mastery overlaid */
    private List<PrerequisiteStatusView> prerequisitePanel(NodeView topic, NodeView tree,
                                                           Map<UUID, NodeView> byId,
                                                           Map<String, NodeView> byCode,
                                                           Map<UUID, SkillState> skills,
                                                           Map<UUID, Double> effective) {
        Set<UUID> direct = directPrerequisites(topic, tree, byId, byCode);
        List<PrerequisiteStatusView> rows = new ArrayList<>();
        for (UUID prereqId : direct) {
            NodeView node = byId.get(prereqId);
            if (node == null) {
                continue;
            }
            SkillState s = skills.get(prereqId);
            Double eff = s == null ? null : effective.get(prereqId);
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
    private Set<UUID> directPrerequisites(NodeView topic, NodeView tree,
                                          Map<UUID, NodeView> byId, Map<String, NodeView> byCode) {
        Set<UUID> direct = new HashSet<>();
        for (KnowledgeGraphService.PrerequisiteRelation rel : graph.prerequisiteRelations(
                tree.id())) {
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

    private LessonActionView decide(UUID learnerId, NodeView tree, NodeView topic,
                                    Map<UUID, NodeView> byId, Map<String, NodeView> byCode,
                                    Map<UUID, SkillState> skills,
                                    Map<UUID, MisconceptionState> misconceptions,
                                    Map<UUID, Double> effective,
                                    List<EvidenceFactView> evidence, Instant now) {

        // (1) prerequisite gate — direct prerequisites of the selected topic
        Set<UUID> directPrereqs = directPrerequisites(topic, tree, byId, byCode);
        record WeakPrereq(NodeView node, double eff, int attempts) { }
        List<WeakPrereq> weakPrereqs = new ArrayList<>();
        for (UUID prereqId : directPrereqs) {
            NodeView node = byId.get(prereqId);
            if (node == null) {
                continue;   // outside this subject's subtree — isolation wins
            }
            SkillState s = skills.get(prereqId);
            if (s == null) {
                if (node != null) {
                    evidence.add(new EvidenceFactView("prerequisite " + node.code(),
                            "not yet measured — not a blocker"));
                }
                continue;
            }
            Double eff = effective.get(prereqId);
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
            return practiceAction(ActionType.REMEDIATE_PREREQUISITE, ReasonCode.PREREQUISITE_WEAK,
                    weakest.node(),
                    "Prerequisite " + weakest.node().code() + " (" + weakest.node().title()
                            + ") is measured weak (" + fmt(weakest.eff()) + " over "
                            + weakest.attempts() + " attempts) — strengthen the foundation"
                            + " before " + topic.code());
        }

        // (2) active misconception attached to this topic (children of type
        // MISCONCEPTION in the folded tree)
        NodeView strongestMisconception = null;
        MisconceptionState strongestState = null;
        if (topic.children() != null) {
            for (NodeView child : topic.children()) {
                if (!"MISCONCEPTION".equals(child.type())) {
                    continue;
                }
                MisconceptionState m = misconceptions.get(child.id());
                if (m == null || m.probability() < learnerProperties.bdt().activeThreshold()) {
                    continue;
                }
                if (strongestState == null || m.probability() > strongestState.probability()) {
                    strongestState = m;
                    strongestMisconception = child;
                }
            }
        }
        if (strongestState != null) {
            evidence.add(new EvidenceFactView("misconception " + strongestMisconception.code(),
                    "probability " + fmt(strongestState.probability()) + " from "
                            + strongestState.evidenceCount() + " evidence item(s)"));
            // validated corrective concept?
            for (ConceptDependencyGraph.Edge edge : conceptGraph.edges(SemanticRelation.REMEDIATED_BY)) {
                if (strongestMisconception.code() != null
                        && strongestMisconception.code().equals(edge.source())) {
                    NodeView corrective = byCode.get(edge.target());
                    if (corrective != null) {
                        return practiceAction(ActionType.STUDY_CORRECTIVE,
                                ReasonCode.MISCONCEPTION_REMEDIATION, corrective,
                                "Misconception \"" + strongestMisconception.title()
                                        + "\" (probability " + fmt(strongestState.probability())
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
                            + strongestState.evidenceCount() + " evidence items) — ask the Tutor"
                            + " for a grounded explanation before practising");
        }

        // (3) review due on the topic
        SkillState skill = skills.get(topic.id());
        boolean hasReview = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING)
                .stream().anyMatch(r -> topic.id().equals(r.nodeId()));
        if (hasReview && skill != null) {
            evidence.add(new EvidenceFactView("review schedule",
                    "retrieval practice due (decay-triggered)"));
            return practiceAction(ActionType.REVIEW_TOPIC, ReasonCode.DUE_REVIEW, topic,
                    "Retrieval practice is due on " + topic.code() + " — last practised "
                            + (skill.lastPracticedAt() == null ? "never in this window"
                            : skill.lastPracticedAt().toString())
                            + ", decay-adjusted mastery " + fmt(effective.get(topic.id())));
        }

        // (4) fluency gap
        if (skill != null && skill.proceduralFluencyGap() != null
                && skill.proceduralFluencyGap() >= properties.fluencyGapThreshold()) {
            evidence.add(new EvidenceFactView("fluency gap (untimed − timed accuracy)",
                    fmt(skill.proceduralFluencyGap()) + " over " + skill.attempts() + " attempts"));
            return practiceAction(ActionType.TIMED_PRACTICE, ReasonCode.FLUENCY_GAP, topic,
                    "Untimed-vs-timed accuracy gap " + fmt(skill.proceduralFluencyGap())
                            + " over " + skill.attempts() + " attempts — practise under timed"
                            + " conditions");
        }

        // (5) established weak mastery
        if (skill != null && skill.attempts() >= properties.minAttemptsForWeakness()) {
            Double eff = effective.get(topic.id());
            if (eff != null && eff < properties.weakMasteryCeiling()) {
                return practiceAction(ActionType.PRACTISE_QUESTIONS, ReasonCode.LOW_MASTERY,
                        topic, "Measured mastery " + fmt(eff) + " over " + skill.attempts()
                                + " attempts is below the weak ceiling ("
                                + fmt(properties.weakMasteryCeiling()) + ") — keep practising "
                                + topic.code());
            }
        }

        // (6) tutor-engaged but never practised (NBA T7a), with V23 structured
        // signals: doubt/misconception-classified asks make the reason honest
        // about WHAT the engagement was
        Instant tutorWindow = now.minus(java.time.Duration.ofDays(
                properties.tutorEngagementWindowDays()));
        List<TutorTopicEngagement> topicAsks = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, tutorWindow)
                .stream().filter(e -> topic.id().equals(e.nodeId())).toList();
        long asks = topicAsks.size();
        if (asks > 0 && skill == null) {
            Map<String, Long> signalCounts = new HashMap<>();
            for (TutorTopicEngagement e : topicAsks) {
                signalCounts.merge(e.signalType() == null ? "TOPIC_ENGAGEMENT" : e.signalType(),
                        1L, Long::sum);
            }
            evidence.add(new EvidenceFactView("tutor engagement",
                    asks + " ask(s) in the last " + properties.tutorEngagementWindowDays()
                            + " day(s), no attempt evidence yet"));
            evidence.add(new EvidenceFactView("engagement signals", signalCounts.toString()));
            String signalNote = signalCounts.containsKey("DOUBT_SIGNAL")
                    ? " — including confusion you reported yourself"
                    : signalCounts.containsKey("MISCONCEPTION_RELATED")
                    ? " — including a question linked to an active misconception"
                    : "";
            return practiceAction(ActionType.PRACTISE_QUESTIONS, ReasonCode.TUTOR_ENGAGED, topic,
                    "You asked the Tutor " + asks + " time(s) about " + topic.code() + " but have "
                            + "no attempt evidence yet" + signalNote
                            + " — convert the curiosity into practice");
        }

        // (7) no attempt evidence — start the topic
        if (skill == null) {
            return practiceAction(ActionType.PRACTISE_QUESTIONS, ReasonCode.INSUFFICIENT_COVERAGE,
                    topic, "No attempt evidence on " + topic.code() + " yet — start with the "
                            + "first validated question to find your level (diagnostic practice)");
        }

        // (8) mastered — advance in curriculum order (doubt-signalled topics first)
        Instant advanceWindow = now.minus(java.time.Duration.ofDays(
                properties.tutorEngagementWindowDays()));
        Set<UUID> confusedTopics = new HashSet<>();
        for (TutorTopicEngagement e : engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, advanceWindow)) {
            if ("DOUBT_SIGNAL".equals(e.signalType())
                    || "MISCONCEPTION_RELATED".equals(e.signalType())) {
                confusedTopics.add(e.nodeId());
            }
        }
        return advance(tree, topic, byId, skills, effective, evidence, confusedTopics);
    }

    /** next topic in curriculum order that still has work to do, or honest completion */
    private LessonActionView advance(NodeView tree, NodeView topic,
                                     Map<UUID, NodeView> byId,
                                     Map<UUID, SkillState> skills,
                                     Map<UUID, Double> effective,
                                     List<EvidenceFactView> evidence,
                                     Set<UUID> confusedTopics) {
        // practicable candidates follow the NBA T7 convention: TOPIC/SUBTOPIC
        // nodes (never SUBJECT/UNIT/MISCONCEPTION) with servable questions.
        // Counts come from ONE batched activeWithin query (per-node queries
        // would reintroduce the N+1 shape §8 of the perf directive bans).
        Map<UUID, Long> servableCounts = new HashMap<>();
        for (StudentQuestionView q : servableQuestions.activeWithin(byId.keySet())) {
            if (q.primaryTopicNodeId() != null) {
                servableCounts.merge(q.primaryTopicNodeId(), 1L, Long::sum);
            }
        }
        List<NodeView> order = curriculumOrder(tree);
        NodeView best = null;
        String bestReason = null;
        // pass 1 (§3 wiring): an unstarted topic the learner reported confusion
        // about jumps the queue — their own doubt signal outranks curriculum order
        if (!confusedTopics.isEmpty()) {
            for (NodeView node : order) {
                if (!"TOPIC".equals(node.type()) && !"SUBTOPIC".equals(node.type())) {
                    continue;
                }
                if (node.id().equals(topic.id()) || !confusedTopics.contains(node.id())) {
                    continue;
                }
                if (servableCounts.getOrDefault(node.id(), 0L) <= 0) {
                    continue;
                }
                if (!skills.containsKey(node.id())) {
                    best = node;
                    bestReason = "you reported confusion here and it is not yet started";
                    break;
                }
            }
        }
        // pass 2: the standing rule — first unstarted, then first weak, in order
        if (best == null) {
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
                SkillState s = skills.get(node.id());
                if (s == null) {
                    best = node;
                    bestReason = "not yet started";
                    break;   // first unstarted topic in curriculum order
                }
                Double eff = effective.get(node.id());
                if (eff != null && eff < properties.weakMasteryCeiling()) {
                    best = node;
                    bestReason = "measured " + fmt(eff) + " (weak)";
                    break;   // first weak topic in curriculum order
                }
            }
        }
        if (best != null) {
            evidence.add(new EvidenceFactView("mastered " + topic.code(),
                    fmt(effective.get(topic.id())) + " over "
                            + skills.get(topic.id()).attempts() + " attempts"));
            evidence.add(new EvidenceFactView("next in curriculum order",
                    best.code() + " — " + bestReason));
            return practiceAction(ActionType.ADVANCE_TOPIC, ReasonCode.TOPIC_MASTERED, best,
                    topic.code() + " is mastered (decay-adjusted mastery "
                            + fmt(effective.get(topic.id())) + ") — move on to " + best.code()
                            + " (" + best.title() + "): " + bestReason);
        }
        evidence.add(new EvidenceFactView("curriculum scan",
                "every topic in this subject is measured strong or has no servable work left"));
        return practiceAction(ActionType.REVIEW_TOPIC, ReasonCode.TOPIC_MASTERED, topic,
                topic.code() + " is mastered and every curriculum topic is measured strong — "
                        + "consolidate with a review round here, or pick a new subject area");
    }

    /** attach the deterministic starter question (first servable, difficulty order) */
    private LessonActionView practiceAction(ActionType type, ReasonCode reason, NodeView target,
                                            String detail) {
        List<StudentQuestionView> servable = servableQuestions.activeByTopic(target.id());
        UUID questionId = servable.isEmpty() ? null : servable.get(0).id();
        return new LessonActionView(type, reason, target.id(), target.code(), target.title(),
                questionId, servable.size(), detail);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private TopicStatusView topicStatus(UUID learnerId, NodeView topic, SkillState skill,
                                        Map<UUID, Double> effective,
                                        Map<UUID, MisconceptionState> misconceptions,
                                        Instant now) {
        int attempts = skill == null ? 0 : skill.attempts();
        String coverage = skill == null ? "UNMEASURED"
                : attempts < properties.minAttemptsForWeakness() ? "PARTIAL" : "ESTABLISHED";
        boolean reviewDue = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING)
                .stream().anyMatch(r -> topic.id().equals(r.nodeId()));
        Double misProb = null;
        if (topic.children() != null) {
            for (NodeView child : topic.children()) {
                if ("MISCONCEPTION".equals(child.type())) {
                    MisconceptionState m = misconceptions.get(child.id());
                    if (m != null && (misProb == null || m.probability() > misProb)) {
                        misProb = m.probability();
                    }
                }
            }
        }
        Instant tutorWindow = now.minus(java.time.Duration.ofDays(
                properties.tutorEngagementWindowDays()));
        long asks = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, tutorWindow)
                .stream().filter(e -> topic.id().equals(e.nodeId())).count();
        return new TopicStatusView(coverage, attempts,
                skill == null ? null : skill.mastery(),
                skill == null ? null : effective.get(topic.id()),
                reviewDue, misProb,
                skill == null ? null : skill.proceduralFluencyGap(),
                asks, servableQuestions.activeByTopic(topic.id()).size());
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
