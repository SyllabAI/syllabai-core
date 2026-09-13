package com.syllabai.recommendation;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.recommendation.ConceptDependencyGraph.Edge;
import com.syllabai.recommendation.ConceptDependencyGraph.SemanticRelation;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.decay.DecayParams;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.NextBestActionView;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
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
 * Deterministic next-best-learning-action engine (F-092 minimal Cycle-1 slice,
 * ADR-017 learning-first baseline: {@code nba-rules/v1.1}).
 *
 * <p>Hard constraints before any ranking (RECOMMENDATION_SYSTEM_ARCHITECTURE.md §4):
 * every candidate is drawn from the requested subject root's subtree
 * (subject/curriculum isolation), question references are gated through
 * {@link ServableQuestionService} (content validation), and reasons are
 * structured codes with evidence-derived details — never LLM-invented, never
 * causal claims. Ranking is fully deterministic (same learner state + same
 * clock ⇒ same actions), which preserves the baseline for future model
 * evaluation (§22).</p>
 *
 * <p>Rule tiers, most pedagogically urgent first: due retrieval (decay) →
 * prerequisite remediation → low-mark problem questions → suspected
 * misconceptions → fluency gaps → weak measured mastery → uncovered topics
 * (constrained exploration, the architecture's replacement for random
 * epsilon-greedy). One action per target topic keeps the portfolio diverse (§16);
 * output is capped at {@code maxActions}.</p>
 *
 * <p><strong>v1.1 (2026-09-13):</strong> the settled T-C11 concept graph joins the
 * engine as a structured dependency layer ({@link ConceptDependencyGraph}),
 * matched to the subject subtree by KG node <em>code</em>. Two graph-aware
 * candidate stages extend the prerequisite and misconception tiers: T2b
 * (validated {@code REQUIRES_PREREQUISITE} chains nominate remediation targets)
 * and T4b (validated {@code REMEDIATED_BY} edges nominate corrective concepts).
 * The graph never overrides deterministic learner evidence and never invents
 * mastery: every graph candidate still requires measured weakness or an active
 * BDT state on the learner's side, a prerequisite measured strong is skipped
 * in favour of the evidence, and only HUMAN_VALIDATED relationships can enter
 * (held/REVIEW_REQUIRED edges are excluded at the graph layer by construction).
 * With no applicable validated relationship the tiers below are unchanged —
 * existing behaviour is preserved by design.</p>
 *
 * <p>Read-only by construction: consumes the same verified read services as the
 * learner-state and personalized-graph endpoints (the T-028/T-034 pattern —
 * server composes, no client-side joins, no new persistence, no parallel
 * tracking model).</p>
 */
@Service
@Transactional(readOnly = true)
public class NextBestActionService {

    /** v1.1: graph-aware candidate sources (T-C11 settled dependency layer) added; tiers and thresholds unchanged. */
    public static final String POLICY = "nba-rules/v1.1";

    private final KnowledgeGraphService graph;
    private final LearnerModelService learnerModel;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties learnerProperties;
    private final RecommendationProperties properties;
    private final AnswerRepository answers;
    private final ServableQuestionService servableQuestions;
    private final ConceptDependencyGraph conceptGraph;

    public NextBestActionService(KnowledgeGraphService graph,
                                 LearnerModelService learnerModel,
                                 ReviewScheduleRepository reviewSchedules,
                                 EbbinghausDecayService decayService,
                                 LearnerProperties learnerProperties,
                                 RecommendationProperties properties,
                                 AnswerRepository answers,
                                 ServableQuestionService servableQuestions,
                                 ConceptDependencyGraph conceptGraph) {
        this.graph = graph;
        this.learnerModel = learnerModel;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.learnerProperties = learnerProperties;
        this.properties = properties;
        this.answers = answers;
        this.servableQuestions = servableQuestions;
        this.conceptGraph = conceptGraph;
    }

    public NextBestActionsView actionsFor(UUID learnerId, UUID rootId) {
        Instant now = Instant.now();
        DecayParams decayParams = learnerProperties.decay().toParams();

        NodeView tree = graph.treeWithMisconceptions(rootId);

        // subject-scoped evidence maps (nodes outside the subtree are ignored —
        // hard subject/curriculum isolation, not a filter applied after ranking)
        Map<UUID, SkillState> skills = new HashMap<>();
        for (SkillState s : learnerModel.skillStates(learnerId)) {
            skills.put(s.nodeId(), s);
        }
        Map<UUID, MisconceptionState> misconceptions = new HashMap<>();
        for (MisconceptionState m : learnerModel.misconceptionStates(learnerId)) {
            misconceptions.put(m.misconceptionNodeId(), m);
        }

        // flatten the subtree once: node registry + parent-of (misconceptions attach
        // as children of topics) + effective mastery for practised nodes + the
        // code registry the T-C11 concept graph joins against (node code is the
        // stable identity shared by both stores)
        Map<UUID, NodeView> byId = new HashMap<>();
        Map<UUID, UUID> parentOf = new HashMap<>();
        Map<String, NodeView> byCode = new HashMap<>();
        collect(tree, null, byId, parentOf, byCode);
        Map<UUID, Double> effective = new HashMap<>();
        for (Map.Entry<UUID, SkillState> e : skills.entrySet()) {
            if (byId.containsKey(e.getKey())) {
                SkillState s = e.getValue();
                effective.put(s.nodeId(), decayService.decayed(
                        s.mastery(), s.lastPracticedAt(), now, decayParams));
            }
        }
        Map<UUID, Integer> servableCount = new HashMap<>();   // per-request cache
        Set<UUID> topicsWithActions = new HashSet<>();        // one action per topic
        List<NextBestActionView> ranked = new ArrayList<>();

        // T1 — due retrieval (decay-triggered reviews), overdue first
        // (sorted here rather than trusting the repository's ordering contract,
        // so the "overdue first" rule is self-contained and testable)
        List<ReviewSchedule> pending = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING)
                .stream()
                .filter(r -> byId.containsKey(r.nodeId()))
                .sorted(java.util.Comparator.comparing(ReviewSchedule::dueAt))
                .toList();
        for (ReviewSchedule r : pending) {
            if (ranked.size() >= properties.maxActions()) break;
            NodeView node = byId.get(r.nodeId());
            if (!topicsWithActions.add(node.id())) continue;
            boolean overdue = r.dueAt().isBefore(now);
            ranked.add(new NextBestActionView(0, ActionType.REVIEW_TOPIC, ReasonCode.DUE_REVIEW,
                    node.id(), node.code(), node.title(), null,
                    servableCount(node, servableCount),
                    "Retrieval practice due (scheduled " + r.dueAt() + ", "
                            + r.reason().name().toLowerCase().replace('_', ' ')
                            + (overdue ? ", overdue" : "") + ")"));
        }

        // T2 — prerequisite remediation: dependent topic established weak AND its
        // prerequisite measured weak ⇒ remediate the prerequisite (§4.4 pedagogical
        // safety: prerequisite support outranks downstream practice)
        List<PrerequisiteRelation> relations = graph.prerequisiteRelations(rootId);
        record PrereqCandidate(String dependentCode, String dependentTitle, double dependentEff,
                               UUID prerequisiteId) { }
        List<PrereqCandidate> prereqCandidates = new ArrayList<>();
        for (PrerequisiteRelation rel : relations) {
            SkillState dep = skills.get(rel.dependentNodeId());
            SkillState pre = skills.get(rel.prerequisiteId());
            if (dep == null || pre == null) continue;
            Double depEff = effective.get(dep.nodeId());
            Double preEff = effective.get(pre.nodeId());
            if (depEff == null || preEff == null) continue;
            if (dep.attempts() < properties.minAttemptsForWeakness() || pre.attempts() < 1) continue;
            if (depEff >= properties.weakMasteryCeiling() || preEff >= properties.weakMasteryCeiling()) continue;
            NodeView depNode = byId.get(rel.dependentNodeId());
            prereqCandidates.add(new PrereqCandidate(
                    depNode.code(), depNode.title(), depEff, rel.prerequisiteId()));
        }
        prereqCandidates.sort(Comparator.comparing(PrereqCandidate::dependentCode));
        for (PrereqCandidate c : prereqCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            NodeView node = byId.get(c.prerequisiteId());
            if (!topicsWithActions.add(node.id())) continue;
            double preEff = effective.get(c.prerequisiteId());
            ranked.add(new NextBestActionView(0, ActionType.REVIEW_PREREQUISITE,
                    ReasonCode.PREREQUISITE_WEAK,
                    node.id(), node.code(), node.title(), null,
                    servableCount(node, servableCount),
                    "Prerequisite of " + c.dependentCode() + " (" + c.dependentTitle()
                            + "); measured mastery " + fmt(preEff) + " here and "
                            + fmt(c.dependentEff()) + " on the dependent topic"));
        }

        // T2b — validated prerequisite chain (T-C11 concept graph): the dependent
        // topic is established-weak from measured evidence AND a HUMAN_VALIDATED
        // REQUIRES_PREREQUISITE edge names its prerequisite ⇒ remediate toward the
        // prerequisite. The graph only NOMINATES the target; deterministic learner
        // evidence gates it — a prerequisite measured strong is skipped in favour of
        // the evidence, and an unmeasured prerequisite is reported honestly as
        // unmeasured, never as weak. Both endpoints must resolve inside this
        // subject's subtree (hard subject isolation for graph candidates too).
        record ChainCandidate(String dependentCode, String dependentTitle, double dependentEff,
                               int dependentAttempts, UUID prerequisiteId, String prerequisiteState) { }
        Map<UUID, ChainCandidate> chainByPrerequisite = new HashMap<>();
        for (Edge edge : conceptGraph.edges(SemanticRelation.REQUIRES_PREREQUISITE)) {
            NodeView dependent = byCode.get(edge.source());
            NodeView prerequisite = byCode.get(edge.target());
            if (dependent == null || prerequisite == null) continue;   // not in this subtree
            SkillState dep = skills.get(dependent.id());
            if (dep == null) continue;   // no learner evidence on the dependent — the graph alone never acts
            Double depEff = effective.get(dependent.id());
            if (depEff == null || dep.attempts() < properties.minAttemptsForWeakness()
                    || depEff >= properties.weakMasteryCeiling()) continue;   // dependent not established-weak
            SkillState pre = skills.get(prerequisite.id());
            String prerequisiteState;
            if (pre == null) {
                prerequisiteState = "not yet measured for this learner";
            } else {
                Double preEff = effective.get(prerequisite.id());
                if (preEff != null && pre.attempts() >= 1
                        && preEff >= properties.weakMasteryCeiling()) {
                    continue;   // measured strength overrides the graph's nomination
                }
                prerequisiteState = "measured mastery " + fmt(preEff == null ? pre.mastery() : preEff)
                        + " over " + pre.attempts() + " attempt(s)";
            }
            chainByPrerequisite.putIfAbsent(prerequisite.id(), new ChainCandidate(
                    dependent.code(), dependent.title(), depEff, dep.attempts(),
                    prerequisite.id(), prerequisiteState));
        }
        List<ChainCandidate> chainCandidates = new ArrayList<>(chainByPrerequisite.values());
        chainCandidates.sort(Comparator.comparing(c -> byId.get(c.prerequisiteId()).code()));
        for (ChainCandidate c : chainCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            NodeView node = byId.get(c.prerequisiteId());
            if (!topicsWithActions.add(node.id())) continue;
            ranked.add(new NextBestActionView(0, ActionType.REVIEW_PREREQUISITE,
                    ReasonCode.VALIDATED_PREREQUISITE_CHAIN,
                    node.id(), node.code(), node.title(), null,
                    servableCount(node, servableCount),
                    "Validated prerequisite chain: " + c.dependentCode() + " ("
                            + c.dependentTitle() + ") measured " + fmt(c.dependentEff())
                            + " over " + c.dependentAttempts() + " attempts requires "
                            + node.code() + " — prerequisite " + c.prerequisiteState()
                            + "; strengthen the foundation first"));
        }

        // T3 — low-mark problem questions: most recent graded answers at/below the
        // ratio threshold, only where the question is still servable and its primary
        // topic is inside this subject (conservative isolation — secondary
        // question_topics mappings are a documented v1 limitation)
        List<Answer> recent = answers.findByLearnerIdOrderByCreatedAtDesc(learnerId);
        int problemAdded = 0;
        for (Answer a : recent) {
            if (problemAdded >= properties.problemQuestionCap()) break;
            if (ranked.size() >= properties.maxActions()) break;
            if (a.markingState() == Answer.MarkingState.PENDING
                    || a.marksAwarded() == null) continue;
            int max = a.questionPart().marks();
            if (max <= 0 || a.marksAwarded() > max) continue;
            if ((double) a.marksAwarded() / max > properties.problemMarkRatio()) continue;
            UUID questionId = a.attempt().questionId();
            UUID topicId = a.attempt().question().primaryTopicNodeId();
            if (topicId == null || !byId.containsKey(topicId)) continue;
            if (!servableQuestions.isServable(questionId)) continue;
            NodeView node = byId.get(topicId);
            if (!topicsWithActions.add(node.id())) continue;
            problemAdded++;
            ranked.add(new NextBestActionView(0, ActionType.RETRY_PROBLEM_QUESTION,
                    ReasonCode.PROBLEM_QUESTION,
                    node.id(), node.code(), node.title(), questionId,
                    servableCount(node, servableCount),
                    "Last marked " + a.marksAwarded() + "/" + max + " on part "
                            + a.questionPart().label() + " ("
                            + a.markingState().name().toLowerCase().replace('_', ' ') + ")"));
        }

        // T4 — suspected misconceptions (BDT): active probability at/above the same
        // threshold the learner-state view uses; the Tutor is the Cycle-1
        // intervention surface for a grounded explanation
        record MisconceptionCandidate(double probability, NodeView node, NodeView parentTopic) { }
        List<MisconceptionCandidate> misconceptionCandidates = new ArrayList<>();
        for (Map.Entry<UUID, MisconceptionState> e : misconceptions.entrySet()) {
            NodeView node = byId.get(e.getKey());
            if (node == null) continue;
            MisconceptionState m = e.getValue();
            if (m.probability() < learnerProperties.bdt().activeThreshold()) continue;
            NodeView parent = byId.get(parentOf.get(node.id()));
            misconceptionCandidates.add(new MisconceptionCandidate(m.probability(), node, parent));
        }
        misconceptionCandidates.sort(Comparator
                .comparingDouble(MisconceptionCandidate::probability).reversed()
                .thenComparing(mc -> mc.node().code()));
        for (MisconceptionCandidate mc : misconceptionCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            NodeView node = mc.node();
            if (!topicsWithActions.add(node.id())) continue;
            String parent = mc.parentTopic() == null ? ""
                    : " on " + mc.parentTopic().code() + " (" + mc.parentTopic().title() + ")";
            MisconceptionState m = misconceptions.get(node.id());
            ranked.add(new NextBestActionView(0, ActionType.ASK_TUTOR,
                    ReasonCode.MISCONCEPTION_SUSPECTED,
                    node.id(), node.code(), node.title(), null, 0,
                    "Misconception probability " + fmt(m.probability()) + " from "
                            + m.evidenceCount() + " evidence item(s)" + parent
                            + " — ask the Tutor for a grounded explanation"));
        }

        // T4b — validated misconception remediation (T-C11 concept graph): the
        // learner's BDT evidence is active on a misconception AND a
        // HUMAN_VALIDATED REMEDIATED_BY edge names its corrective concept ⇒
        // surface the corrective action on that concept. The misconception node
        // itself keeps its ASK_TUTOR action above (existing behaviour); this adds
        // the graph-directed leg — what to STUDY to correct it. Both endpoints
        // must resolve inside this subject's subtree (subject isolation).
        record CorrectiveCandidate(double probability, int evidenceCount,
                                   NodeView misconception, NodeView corrective) { }
        List<CorrectiveCandidate> correctiveCandidates = new ArrayList<>();
        for (Edge edge : conceptGraph.edges(SemanticRelation.REMEDIATED_BY)) {
            NodeView misNode = byCode.get(edge.source());
            NodeView corrective = byCode.get(edge.target());
            if (misNode == null || corrective == null) continue;   // not in this subtree
            MisconceptionState m = misconceptions.get(misNode.id());
            if (m == null || m.probability() < learnerProperties.bdt().activeThreshold()) {
                continue;   // no active learner evidence — the graph alone never acts
            }
            correctiveCandidates.add(new CorrectiveCandidate(
                    m.probability(), m.evidenceCount(), misNode, corrective));
        }
        correctiveCandidates.sort(Comparator
                .comparingDouble(CorrectiveCandidate::probability).reversed()
                .thenComparing(cc -> cc.misconception().code())
                .thenComparing(cc -> cc.corrective().code()));
        for (CorrectiveCandidate cc : correctiveCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            NodeView node = cc.corrective();
            if (!topicsWithActions.add(node.id())) continue;
            ranked.add(new NextBestActionView(0, ActionType.REMEDIATE_MISCONCEPTION,
                    ReasonCode.MISCONCEPTION_REMEDIATION,
                    node.id(), node.code(), node.title(), null,
                    servableCount(node, servableCount),
                    "Misconception " + cc.misconception().code() + " ("
                            + cc.misconception().title() + ") probability " + fmt(cc.probability())
                            + " from " + cc.evidenceCount() + " evidence item(s)"
                            + " — validated remediation: study " + node.code() + " ("
                            + node.title() + "), then ask the Tutor for the corrective explanation"));
        }

        // T5 — timed-vs-untimed fluency gaps (Paper B §16 / F-162)
        record FluencyCandidate(double gap, SkillState skill, NodeView node) { }
        List<FluencyCandidate> fluencyCandidates = new ArrayList<>();
        for (Map.Entry<UUID, SkillState> e : skills.entrySet()) {
            NodeView node = byId.get(e.getKey());
            if (node == null || node.type().equals("MISCONCEPTION")) continue;
            SkillState s = e.getValue();
            Double gap = s.proceduralFluencyGap();
            // Signed semantics, matching StruggleInferenceService: the gap is
            // untimed − timed accuracy, so only a POSITIVE gap (the learner does
            // WORSE under timed conditions) justifies a timed-practice action.
            // A large negative gap (better under timed) is not a fluency problem;
            // gating on |gap| here prescribed TIMED_EXERCISE to exactly those learners.
            if (gap == null || gap < properties.fluencyGapThreshold()) continue;
            fluencyCandidates.add(new FluencyCandidate(gap, s, node));
        }
        fluencyCandidates.sort(Comparator
                .comparingDouble((FluencyCandidate fc) -> Math.abs(fc.gap())).reversed()
                .thenComparing(fc -> fc.node().code()));
        for (FluencyCandidate fc : fluencyCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            if (!topicsWithActions.add(fc.node().id())) continue;
            ranked.add(new NextBestActionView(0, ActionType.TIMED_EXERCISE, ReasonCode.FLUENCY_GAP,
                    fc.node().id(), fc.node().code(), fc.node().title(), null,
                    servableCount(fc.node(), servableCount),
                    "Untimed-vs-timed accuracy gap " + fmt(fc.gap()) + " over "
                            + fc.skill().attempts() + " attempts — practise under timed conditions"));
        }

        // T6 — weak measured mastery (only with established evidence)
        record WeakCandidate(double eff, SkillState skill, NodeView node) { }
        List<WeakCandidate> weakCandidates = new ArrayList<>();
        for (Map.Entry<UUID, SkillState> e : skills.entrySet()) {
            NodeView node = byId.get(e.getKey());
            if (node == null || node.type().equals("MISCONCEPTION")) continue;
            SkillState s = e.getValue();
            if (s.attempts() < properties.minAttemptsForWeakness()) continue;
            Double eff = effective.get(s.nodeId());
            if (eff == null || eff >= properties.weakMasteryCeiling()) continue;
            weakCandidates.add(new WeakCandidate(eff, s, node));
        }
        weakCandidates.sort(Comparator
                .comparingDouble(WeakCandidate::eff)
                .thenComparing(wc -> wc.node().code()));
        for (WeakCandidate wc : weakCandidates) {
            if (ranked.size() >= properties.maxActions()) break;
            if (!topicsWithActions.add(wc.node().id())) continue;
            String band = decayParams.bandOf(wc.eff());
            ranked.add(new NextBestActionView(0, ActionType.PRACTISE_QUESTIONS,
                    ReasonCode.LOW_MASTERY,
                    wc.node().id(), wc.node().code(), wc.node().title(), null,
                    servableCount(wc.node(), servableCount),
                    "Measured mastery " + fmt(wc.eff()) + " (band " + band + ") over "
                            + wc.skill().attempts() + " attempts, last practiced "
                            + wc.skill().lastPracticedAt()));
        }

        // T7 — uncovered topics (constrained exploration): curriculum-order topics with
        // no attempt evidence that actually have validated questions to practise
        int uncoveredAdded = 0;
        for (NodeView node : curriculumOrder(tree)) {
            if (uncoveredAdded >= properties.uncoveredTopicCap()) break;
            if (ranked.size() >= properties.maxActions()) break;
            if (!node.type().equals("TOPIC") && !node.type().equals("SUBTOPIC")) continue;
            if (skills.containsKey(node.id())) continue;
            int count = servableCount(node, servableCount);
            if (count <= 0) continue;
            if (!topicsWithActions.add(node.id())) continue;
            uncoveredAdded++;
            ranked.add(new NextBestActionView(0, ActionType.PRACTISE_QUESTIONS,
                    ReasonCode.UNCOVERED_TOPIC,
                    node.id(), node.code(), node.title(), null, count,
                    "No attempt evidence yet; " + count
                            + " validated question(s) available"));
        }

        List<NextBestActionView> actions = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            NextBestActionView a = ranked.get(i);
            actions.add(new NextBestActionView(i + 1, a.actionType(), a.reasonCode(),
                    a.targetNodeId(), a.targetCode(), a.targetTitle(), a.questionId(),
                    a.servableQuestionCount(), a.reasonDetail()));
        }
        return new NextBestActionsView(learnerId, rootId, now, POLICY, List.copyOf(actions));
    }

    // ── internals ──────────────────────────────────────────────────

    private void collect(NodeView node, UUID parentId,
                         Map<UUID, NodeView> byId, Map<UUID, UUID> parentOf,
                         Map<String, NodeView> byCode) {
        byId.put(node.id(), node);
        byCode.putIfAbsent(node.code(), node);   // KG codes are unique (uq_knowledge_node_code)
        if (parentId != null) {
            parentOf.put(node.id(), parentId);
        }
        for (NodeView child : node.children()) {
            collect(child, node.id(), byId, parentOf, byCode);
        }
    }

    /** depth-first curriculum order (spec order — the deterministic exploration walk) */
    private List<NodeView> curriculumOrder(NodeView root) {
        List<NodeView> out = new ArrayList<>();
        walkOrder(root, out);
        return out;
    }

    private void walkOrder(NodeView node, List<NodeView> out) {
        out.add(node);
        for (NodeView child : node.children()) {
            walkOrder(child, out);
        }
    }

    private int servableCount(NodeView node, Map<UUID, Integer> cache) {
        if (node.type().equals("MISCONCEPTION")) {
            return 0;   // questions are never mapped to misconception nodes
        }
        return cache.computeIfAbsent(node.id(), servableQuestions::countServableByTopic);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
