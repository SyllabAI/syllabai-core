package com.syllabai.learner;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.decay.DecayParams;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView.NodeWithStateView;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView.PrerequisiteEdgeView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personalized knowledge-graph read model (F-034): composes the verified KG
 * read services ({@link KnowledgeGraphService}) with the learner model
 * (BKT skill states, BDT misconception states, review schedules) — no new
 * persistence, no new traversal, one server-side join instead of a join in
 * every client. Read-only by construction; nothing here mutates learner state.
 */
@Service
@Transactional(readOnly = true)
public class LearnerKnowledgeGraphService {

    private final KnowledgeGraphService graph;
    private final LearnerModelService learnerModel;
    private final ReviewScheduleRepository reviewSchedules;
    private final EbbinghausDecayService decayService;
    private final LearnerProperties properties;

    public LearnerKnowledgeGraphService(KnowledgeGraphService graph,
                                        LearnerModelService learnerModel,
                                        ReviewScheduleRepository reviewSchedules,
                                        EbbinghausDecayService decayService,
                                        LearnerProperties properties) {
        this.graph = graph;
        this.learnerModel = learnerModel;
        this.reviewSchedules = reviewSchedules;
        this.decayService = decayService;
        this.properties = properties;
    }

    public LearnerKnowledgeGraphView graphFor(UUID learnerId, UUID rootId) {
        Instant now = Instant.now();
        DecayParams decayParams = properties.decay().toParams();

        NodeView tree = graph.treeWithMisconceptions(rootId);
        Map<UUID, SkillState> skills = new HashMap<>();
        for (SkillState s : learnerModel.skillStates(learnerId)) {
            skills.put(s.nodeId(), s);
        }
        Map<UUID, MisconceptionState> misconceptions = new HashMap<>();
        for (MisconceptionState m : learnerModel.misconceptionStates(learnerId)) {
            misconceptions.put(m.misconceptionNodeId(), m);
        }
        // earliest PENDING review per node (the repository returns dueAt-ascending)
        Map<UUID, ReviewSchedule> earliestReview = new HashMap<>();
        for (ReviewSchedule r : reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                learnerId, ReviewSchedule.Status.PENDING)) {
            earliestReview.merge(r.nodeId(), r,
                    (a, b) -> a.dueAt().isBefore(b.dueAt()) ? a : b);
        }

        List<NodeWithStateView> nodes = new ArrayList<>();
        Map<UUID, NodeWithStateView> byId = new HashMap<>();
        walk(tree, skills, misconceptions, earliestReview, decayParams, now, nodes, byId);

        List<PrerequisiteEdgeView> edges = new ArrayList<>();
        for (PrerequisiteRelation relation : graph.prerequisiteRelations(rootId)) {
            NodeWithStateView prerequisite = byId.get(relation.prerequisiteId());
            NodeWithStateView dependent = byId.get(relation.dependentNodeId());
            // structural invariant: prerequisiteRelations operates on the PART_OF
            // subtree, and the walk covers exactly those nodes — unknown ids
            // would mean a graph inconsistency, so they are skipped, not guessed.
            if (prerequisite != null && dependent != null) {
                edges.add(new PrerequisiteEdgeView(
                        prerequisite.id(), prerequisite.code(),
                        dependent.id(), dependent.code()));
            }
        }

        return new LearnerKnowledgeGraphView(learnerId, tree.id(), tree.code(), tree.title(),
                now, List.copyOf(nodes), List.copyOf(edges));
    }

    // ── internals ──────────────────────────────────────────────────

    private void walk(NodeView node,
                      Map<UUID, SkillState> skills,
                      Map<UUID, MisconceptionState> misconceptions,
                      Map<UUID, ReviewSchedule> earliestReview,
                      DecayParams decayParams,
                      Instant now,
                      List<NodeWithStateView> out,
                      Map<UUID, NodeWithStateView> byId) {
        SkillState skill = skills.get(node.id());
        MisconceptionState misconception = misconceptions.get(node.id());
        ReviewSchedule review = earliestReview.get(node.id());

        Double mastery = null;
        Double effective = null;
        String band = null;
        Integer attempts = null;
        Integer correct = null;
        Instant lastPracticed = null;
        Double fluencyGap = null;
        if (skill != null) {
            mastery = skill.mastery();
            effective = decayService.decayed(
                    skill.mastery(), skill.lastPracticedAt(), now, decayParams);
            band = decayParams.bandOf(effective);
            attempts = skill.attempts();
            correct = skill.correctCount();
            lastPracticed = skill.lastPracticedAt();
            fluencyGap = skill.proceduralFluencyGap();
        }
        Double misconceptionProbability = null;
        Boolean misconceptionActive = null;
        if (misconception != null) {
            misconceptionProbability = misconception.probability();
            misconceptionActive =
                    misconception.probability() >= properties.bdt().activeThreshold();
        }

        NodeWithStateView view = new NodeWithStateView(
                node.id(), node.code(), node.type(), node.title(), node.description(),
                node.children().stream().map(NodeView::id).toList(),
                mastery, effective, band, attempts, correct, lastPracticed, fluencyGap,
                review == null ? null : review.dueAt(),
                review == null ? null : review.reason().name(),
                misconceptionProbability, misconceptionActive);
        out.add(view);
        byId.put(view.id(), view);
        for (NodeView child : node.children()) {
            walk(child, skills, misconceptions, earliestReview, decayParams, now, out, byId);
        }
    }
}
