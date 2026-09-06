package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeGraphService.PrerequisiteRelation;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView.NodeWithStateView;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView.PrerequisiteEdgeView;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The personalized knowledge-graph read model (F-034): the verified KG tree
 * annotated with THIS learner's state — decayed effective mastery and band on
 * practised nodes, honest nulls on unpractised nodes, misconception
 * annotations only on MISCONCEPTION nodes, the earliest pending review per
 * node, and prerequisite edges in the drawable direction.
 */
class LearnerKnowledgeGraphServiceTest {

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final ReviewScheduleRepository reviewSchedules = mock(ReviewScheduleRepository.class);
    private final EbbinghausDecayService decayService = new EbbinghausDecayService();

    private final LearnerProperties properties = new LearnerProperties(
            null,
            new LearnerProperties.Decay(30, 90, 365, 0.45, 0.8, 0.1, 0.6),
            new LearnerProperties.Bdt(0.3, 0.7, 0.1, 0.5),
            null);

    private final LearnerKnowledgeGraphService service = new LearnerKnowledgeGraphService(
            graph, learnerModel, reviewSchedules, decayService, properties);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID rootId = UUID.randomUUID();
    private final UUID unit1Id = UUID.randomUUID();
    private final UUID unit2Id = UUID.randomUUID();
    private final UUID topic1Id = UUID.randomUUID();
    private final UUID topic2Id = UUID.randomUUID();
    private final UUID topic3Id = UUID.randomUUID();
    private final UUID misconceptionId = UUID.randomUUID();

    private final Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);

    @Test
    @DisplayName("practised node carries stored + decayed mastery, band, attempts and fluency gap")
    void practisedNodeAnnotated() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        NodeWithStateView topic1 = node(view, topic1Id);
        double expectedEffective = decayService.decayed(
                0.72, twoDaysAgo, view.asOf(), properties.decay().toParams());

        assertThat(topic1.mastery()).isEqualTo(0.72);
        assertThat(topic1.effectiveMastery()).isCloseTo(expectedEffective, within(1e-12));
        assertThat(topic1.band()).isEqualTo(properties.decay().toParams().bandOf(expectedEffective));
        assertThat(topic1.attempts()).isEqualTo(3);
        assertThat(topic1.correctCount()).isEqualTo(2);
        assertThat(topic1.lastPracticedAt()).isEqualTo(twoDaysAgo);
        assertThat(topic1.proceduralFluencyGap()).isEqualTo(0.31);
    }

    @Test
    @DisplayName("unpractised nodes render honest nulls — never zeros, never a fabricated band")
    void unpractisedNodeHonest() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        NodeWithStateView topic2 = node(view, topic2Id);
        assertThat(topic2.mastery()).isNull();
        assertThat(topic2.effectiveMastery()).isNull();
        assertThat(topic2.band()).isNull();
        assertThat(topic2.attempts()).isNull();
        assertThat(topic2.correctCount()).isNull();
        assertThat(topic2.lastPracticedAt()).isNull();
        assertThat(topic2.proceduralFluencyGap()).isNull();
        assertThat(topic2.reviewDueAt()).isNull();
        assertThat(topic2.reviewReason()).isNull();
        assertThat(topic2.misconceptionProbability()).isNull();
        assertThat(topic2.misconceptionActive()).isNull();
    }

    @Test
    @DisplayName("misconception annotation lands only on the MISCONCEPTION node")
    void misconceptionAnnotated() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        NodeWithStateView misconception = node(view, misconceptionId);
        assertThat(misconception.misconceptionProbability()).isEqualTo(0.75);
        assertThat(misconception.misconceptionActive()).isTrue();     // 0.75 >= 0.5 threshold
        assertThat(misconception.mastery()).isNull();                 // misconceptions carry no BKT state

        // no other node carries misconception annotations
        for (NodeWithStateView n : view.nodes()) {
            if (!n.id().equals(misconceptionId)) {
                assertThat(n.misconceptionProbability()).isNull();
                assertThat(n.misconceptionActive()).isNull();
            }
        }
    }

    @Test
    @DisplayName("the EARLIEST pending review is annotated per node")
    void earliestReviewAnnotated() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        NodeWithStateView topic3 = node(view, topic3Id);
        assertThat(topic3.reviewDueAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(topic3.reviewReason()).isEqualTo("DECAY_CROSSED_THRESHOLD");
    }

    @Test
    @DisplayName("prerequisite edges point prerequisite -> dependent, with codes")
    void prerequisiteEdgeDirection() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        assertThat(view.prerequisiteEdges()).hasSize(1);
        PrerequisiteEdgeView edge = view.prerequisiteEdges().get(0);
        assertThat(edge.prerequisiteId()).isEqualTo(topic2Id);   // T2 is the prerequisite
        assertThat(edge.prerequisiteCode()).isEqualTo("U1-T2");
        assertThat(edge.nodeId()).isEqualTo(topic1Id);           // T1 requires it
        assertThat(edge.nodeCode()).isEqualTo("U1-T1");
    }

    @Test
    @DisplayName("flat nodes are depth-first with childIds preserving tree order; root fields set")
    void structurePreserved() {
        LearnerKnowledgeGraphView view = graphForDefaultState();

        assertThat(view.learnerId()).isEqualTo(learnerId);
        assertThat(view.rootId()).isEqualTo(rootId);
        assertThat(view.rootCode()).isEqualTo("IALCHEM2018");
        assertThat(view.rootTitle()).isEqualTo("IAL Chemistry 2018");
        assertThat(view.asOf()).isNotNull();

        assertThat(view.nodes()).extracting(NodeWithStateView::id).containsExactly(
                rootId, unit1Id, topic1Id, misconceptionId, topic2Id, unit2Id, topic3Id);

        NodeWithStateView root = node(view, rootId);
        assertThat(root.childIds()).containsExactly(unit1Id, unit2Id);
        NodeWithStateView topic1 = node(view, topic1Id);
        assertThat(topic1.childIds()).containsExactly(misconceptionId);
        assertThat(topic1.type()).isEqualTo("TOPIC");
        assertThat(node(view, misconceptionId).type()).isEqualTo("MISCONCEPTION");
    }

    @Test
    @DisplayName("a learner with no state at all still gets the full tree, all annotations null")
    void noStateRendersEmptyAnnotations() {
        when(graph.treeWithMisconceptions(rootId)).thenReturn(defaultTree());
        when(graph.prerequisiteRelations(rootId)).thenReturn(List.of());
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());
        when(learnerModel.misconceptionStates(learnerId)).thenReturn(List.of());
        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                learnerId, ReviewSchedule.Status.PENDING)).thenReturn(List.of());

        LearnerKnowledgeGraphView view = service.graphFor(learnerId, rootId);

        assertThat(view.nodes()).hasSize(7);
        for (NodeWithStateView n : view.nodes()) {
            assertThat(n.mastery()).isNull();
            assertThat(n.band()).isNull();
            assertThat(n.reviewDueAt()).isNull();
            assertThat(n.misconceptionProbability()).isNull();
        }
        assertThat(view.prerequisiteEdges()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────

    private LearnerKnowledgeGraphView graphForDefaultState() {
        when(graph.treeWithMisconceptions(rootId)).thenReturn(defaultTree());
        when(graph.prerequisiteRelations(rootId))
                .thenReturn(List.of(new PrerequisiteRelation(topic2Id, topic1Id)));

        SkillState topic1State = new SkillState(learnerId, topic1Id, 0.1, twoDaysAgo);
        topic1State.recordAttempt(true, 0.5, twoDaysAgo);
        topic1State.recordAttempt(true, 0.72, twoDaysAgo);
        topic1State.recordAttempt(false, 0.72, twoDaysAgo);      // 3 attempts, 2 correct
        topic1State.setProceduralFluencyGap(0.31);
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(topic1State));

        MisconceptionState misconceptionState =
                new MisconceptionState(learnerId, misconceptionId, 0.3, twoDaysAgo);
        misconceptionState.update(0.75, twoDaysAgo);
        when(learnerModel.misconceptionStates(learnerId))
                .thenReturn(List.of(misconceptionState));

        when(reviewSchedules.findByLearnerIdAndStatusOrderByDueAtAsc(
                learnerId, ReviewSchedule.Status.PENDING)).thenReturn(List.of(
                // two pending reviews on topic 3 — the earlier one must win
                new ReviewSchedule(learnerId, topic3Id,
                        Instant.parse("2026-09-03T00:00:00Z"),
                        ReviewSchedule.Reason.TEACHER_ASSIGNED, 0.55),
                new ReviewSchedule(learnerId, topic3Id,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, 0.58)));

        return service.graphFor(learnerId, rootId);
    }

    /** Nested NodeView tree exactly as {@code KnowledgeGraphService.treeWithMisconceptions} builds it. */
    private NodeView defaultTree() {
        NodeView misconception = node(misconceptionId, "U1-T1-M1", "MISCONCEPTION",
                "Moles and grams are interchangeable", null, List.of());
        NodeView topic1 = node(topic1Id, "U1-T1", "TOPIC", "Formulae, Equations and Moles",
                "desc-t1", List.of(misconception));
        NodeView topic2 = node(topic2Id, "U1-T2", "TOPIC", "Bonding and Structure",
                null, List.of());
        NodeView topic3 = node(topic3Id, "U2-T3", "TOPIC", "Energetics I", null, List.of());
        NodeView unit1 = node(unit1Id, "U1", "UNIT", "Unit 1: Core Principles",
                null, List.of(topic1, topic2));
        NodeView unit2 = node(unit2Id, "U2", "UNIT", "Unit 2: Applications",
                null, List.of(topic3));
        return node(rootId, "IALCHEM2018", "SUBJECT", "IAL Chemistry 2018",
                null, List.of(unit1, unit2));
    }

    private static NodeView node(UUID id, String code, String type, String title,
                                 String description, List<NodeView> children) {
        return new NodeView(id, code, type, title, description, "VALIDATED",
                "test-provenance", children);
    }

    private static NodeWithStateView node(LearnerKnowledgeGraphView view, UUID id) {
        return view.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }
}
