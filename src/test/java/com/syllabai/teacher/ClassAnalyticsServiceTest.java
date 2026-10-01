package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.TutorTopicEngagement;
import com.syllabai.learner.TutorTopicEngagementRepository;
import com.syllabai.recommendation.RecommendationProperties;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teacher class intelligence (sprint 2 §2–§5): the aggregation must separate
 * evidence kinds (tutor engagement is NEVER mastery), represent unmeasured
 * honestly (null means, UNMEASURED bands — never fabricated values), reuse
 * the learner-facing thresholds (weak ceiling 0.45, BDT active 0.5, evidence
 * floor 2), scope strictly by subject root, and produce deterministic
 * teacher-facing orders.
 */
class ClassAnalyticsServiceTest {

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final MisconceptionStateRepository misconceptionStates =
            mock(MisconceptionStateRepository.class);
    private final TutorTopicEngagementRepository engagements =
            mock(TutorTopicEngagementRepository.class);
    private final ReviewScheduleRepository reviewSchedules =
            mock(ReviewScheduleRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ServableQuestionService servableQuestions =
            mock(ServableQuestionService.class);

    private final LearnerProperties learnerProperties = new LearnerProperties(null, null, null, null);
    private final RecommendationProperties recommendationProperties =
            new RecommendationProperties(0.45, 0.2, 0.5, 2, 8, 2, 2, 14);

    private final ClassAnalyticsService service = new ClassAnalyticsService(
            graph, skillStates, misconceptionStates, new BdtEngine(), engagements,
            reviewSchedules, attempts, answers, users, servableQuestions,
            learnerProperties, recommendationProperties);

    private final UUID root = UUID.randomUUID();
    private final UUID section = UUID.randomUUID();
    private final UUID topicA = UUID.randomUUID();     // 4CH1-1.1
    private final UUID topicB = UUID.randomUUID();     // 4CH1-1.2
    private final UUID topicC = UUID.randomUUID();     // 4CH1-1.3 (never measured)
    private final UUID miscoA = UUID.randomUUID();     // misconception under topicA
    private final UUID outside = UUID.randomUUID();    // another subject's topic
    private final UUID conceptX = UUID.randomUUID();   // T-C11 concept anchored on topicA
    private final UUID practicalB = UUID.randomUUID(); // required practical under topicB

    private final UUID learner1 = UUID.randomUUID();
    private final UUID learner2 = UUID.randomUUID();
    private final UUID learner3 = UUID.randomUUID();   // never measured anywhere

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    // ── fixtures ────────────────────────────────────────────────────────

    private NodeView node(UUID id, String code, String type, String title,
                          List<NodeView> children) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null, null,
                children);
    }

    /** the 4CH1-shaped tree: root → section → topics (+misconception under A,
     *  + T-C11 concept layer: concept anchored on A, required practical under B) */
    private NodeView subjectTree() {
        return node(root, "4CH1", "SUBJECT", "Edexcel IGCSE Chemistry 4CH1", List.of(
                node(section, "4CH1-S1", "UNIT", "Section 1", List.of(
                        node(topicA, "4CH1-1.1", "TOPIC", "Atoms", List.of(
                                node(miscoA, "MIS-A", "MISCONCEPTION", "Atoms are lost", List.of()),
                                node(conceptX, "4CH1-CON-SOLUBILITY", "CONCEPT", "Solubility", List.of()))),
                        node(topicB, "4CH1-1.2", "TOPIC", "Moles", List.of(
                                node(practicalB, "4CH1-PR-01", "SUBTOPIC",
                                        "Required practical: solubility", List.of()))),
                        node(topicC, "4CH1-1.3", "TOPIC", "Bonding", List.of())))));
    }

    private User user(UUID id, String name) {
        return TestIds.withId(new User(
                name.toLowerCase().replace(' ', '.') + "@test", "hash", name,
                Set.of(Role.STUDENT)), id);
    }

    private SkillState skill(UUID learner, UUID node, double mastery, int attempts) {
        SkillState s = new SkillState(learner, node, mastery, T0);
        for (int i = 0; i < attempts; i++) {
            s.recordAttempt(true, mastery, T0);
        }
        return s;
    }

    private MisconceptionState misco(UUID learner, UUID node, double probability) {
        MisconceptionState m = new MisconceptionState(learner, node, probability, T0);
        m.update(probability, T0);
        return m;
    }

    private void givenTree() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
    }

    private void givenNoEvidence() {
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of());
        when(misconceptionStates.findByMisconceptionNodeIdIn(anyCollection()))
                .thenReturn(List.of());
        when(engagements.findByNodeIdIn(anyCollection())).thenReturn(List.of());
        when(reviewSchedules.findByStatusAndDueAtLessThanEqualAndNodeIdIn(
                any(), any(), anyCollection())).thenReturn(List.of());
        when(attempts.aggregateByLearnerSinceWithin(any(), anyCollection()))
                .thenReturn(List.of());
        when(servableQuestions.activeWithin(anyCollection())).thenReturn(List.of());
        when(answers.countByMarkingStateWithin(any(), anyCollection())).thenReturn(0L);
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of());
        when(graph.prerequisiteRelations(root)).thenReturn(List.of());
        when(graph.conceptAnchorsWithin(root)).thenReturn(List.of());
    }

    // ── honest empty state ──────────────────────────────────────────────

    @Test
    @DisplayName("empty class: every topic UNMEASURED with null mean, learners honestly UNMEASURED")
    void emptyStateIsHonest() {
        givenTree();
        givenNoEvidence();
        User u = user(learner3, "Zed Unmeasured");
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of(u));

        var overview = service.overview(root);
        assertThat(overview.enrolledLearners()).isEqualTo(1);
        assertThat(overview.learnersWithEvidence()).isZero();
        assertThat(overview.learnersRecentlyActive()).isZero();
        assertThat(overview.totalTopics()).isEqualTo(7);   // root+section+3 topics +concept +practical
        assertThat(overview.measuredTopics()).isZero();
        assertThat(overview.weakPrerequisites()).isEmpty();
        assertThat(overview.policy()).isEqualTo("class-analytics/v1");
        assertThat(overview.topics()).allSatisfy(t -> {
            assertThat(t.learnersMeasured()).isZero();
            assertThat(t.meanMastery()).isNull();
            assertThat(t.masteryBand()).isEqualTo("UNMEASURED");
            assertThat(t.servableQuestions()).isZero();
        });

        var rows = service.learners(root);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.evidenceState()).isEqualTo("UNMEASURED");
        assertThat(row.meanMastery()).isNull();
        assertThat(row.evidenceBackedAttempts()).isZero();
        assertThat(row.activeMisconceptions()).isZero();
        assertThat(row.tutorEngagements()).isZero();
        assertThat(row.weakestTopics()).isEmpty();
        assertThat(row.misconceptionSignals()).isEmpty();
    }

    // ── aggregation correctness + evidence separation ───────────────────

    @Test
    @DisplayName("class mastery aggregates the measured; engagement stays OUT of mastery")
    void aggregatesMasteryAndKeepsEngagementSeparate() {
        givenTree();
        givenNoEvidence();
        // two measured learners on topicA: 0.2 and 0.6 → mean 0.4 (LOW band)
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.2, 3),
                skill(learner2, topicA, 0.6, 4)));
        // learner1 asked the tutor about topicB — engagement, NOT weakness
        when(engagements.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                new TutorTopicEngagement(learner1, topicB, T0, 1, false, "groq",
                        "DOUBT_SIGNAL")));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One"), user(learner2, "Beta Two")));

        var overview = service.overview(root);
        var cell = overview.topics().stream()
                .filter(t -> t.nodeId().equals(topicA)).findFirst().orElseThrow();
        assertThat(cell.learnersMeasured()).isEqualTo(2);
        assertThat(cell.meanMastery()).isEqualTo(0.4);
        assertThat(cell.masteryBand()).isEqualTo("LOW");
        assertThat(cell.evidenceBackedAttempts()).isEqualTo(7);

        // topicB: NO skill states — engagement must NOT create mastery
        var engaged = overview.topics().stream()
                .filter(t -> t.nodeId().equals(topicB)).findFirst().orElseThrow();
        assertThat(engaged.learnersMeasured()).isZero();
        assertThat(engaged.meanMastery()).isNull();
        assertThat(engaged.masteryBand()).isEqualTo("UNMEASURED");
        assertThat(engaged.tutorEngagements()).isEqualTo(1);

        assertThat(overview.learnersWithEvidence()).isEqualTo(2);

        // learner rows: engagement counted in its own fields, mastery untouched
        var row1 = service.learners(root).stream()
                .filter(r -> r.learnerId().equals(learner1)).findFirst().orElseThrow();
        assertThat(row1.tutorEngagements()).isEqualTo(1);
        assertThat(row1.tutorSignalCounts()).containsEntry("DOUBT_SIGNAL", 1);
        assertThat(row1.lastTutorEngagementAt()).isEqualTo(T0);
        assertThat(row1.topicsMeasured()).isEqualTo(1);
        assertThat(row1.meanMastery()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("misconception concentration: only at/above the BDT active threshold counts")
    void misconceptionConcentrationRespectsThreshold() {
        givenTree();
        givenNoEvidence();
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.3, 2),
                skill(learner2, topicA, 0.7, 2)));
        // learner1 active (0.9), learner2 below threshold (0.2)
        when(misconceptionStates.findByMisconceptionNodeIdIn(anyCollection())).thenReturn(List.of(
                misco(learner1, miscoA, 0.9),
                misco(learner2, miscoA, 0.2)));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One"), user(learner2, "Beta Two")));

        var cell = service.overview(root).topics().stream()
                .filter(t -> t.nodeId().equals(topicA)).findFirst().orElseThrow();
        assertThat(cell.learnersWithActiveMisconception()).isEqualTo(1);
        assertThat(cell.activeMisconceptionSignals()).isEqualTo(1);

        var rows = service.learners(root);
        var r1 = rows.stream().filter(r -> r.learnerId().equals(learner1))
                .findFirst().orElseThrow();
        var r2 = rows.stream().filter(r -> r.learnerId().equals(learner2))
                .findFirst().orElseThrow();
        assertThat(r1.activeMisconceptions()).isEqualTo(1);
        assertThat(r1.misconceptionSignals()).hasSize(1);
        assertThat(r1.misconceptionSignals().get(0).code()).isEqualTo("MIS-A");
        assertThat(r1.misconceptionSignals().get(0).parentTopicCode()).isEqualTo("4CH1-1.1");
        assertThat(r2.activeMisconceptions()).isZero();   // 0.2 < 0.5 — honest
    }

    // ── weak prerequisites ──────────────────────────────────────────────

    @Test
    @DisplayName("weak prerequisites: measured-weak prerequisite listed with dependents; unmeasured never claimed")
    void weakPrerequisitesMeasuredOnly() {
        givenTree();
        givenNoEvidence();
        // topicA (prerequisite) measured weak by two learners; topicB depends on it
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.1, 4),
                skill(learner2, topicA, 0.3, 4),
                skill(learner1, topicB, 0.5, 2)));
        when(graph.prerequisiteRelations(root)).thenReturn(List.of(
                new KnowledgeGraphService.PrerequisiteRelation(topicA, topicB)));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One"), user(learner2, "Beta Two")));

        var weak = service.overview(root).weakPrerequisites();
        assertThat(weak).hasSize(1);
        assertThat(weak.get(0).prerequisiteNodeId()).isEqualTo(topicA);
        assertThat(weak.get(0).meanMastery()).isEqualTo(0.2);
        assertThat(weak.get(0).masteryBand()).isEqualTo("LOW");
        assertThat(weak.get(0).dependents()).hasSize(1);
        assertThat(weak.get(0).dependents().get(0).nodeId()).isEqualTo(topicB);
        assertThat(weak.get(0).dependents().get(0).meanMastery()).isEqualTo(0.5);

        // unmeasured prerequisite (topicC) must NEVER appear as weak
        when(graph.prerequisiteRelations(root)).thenReturn(List.of(
                new KnowledgeGraphService.PrerequisiteRelation(topicC, topicB)));
        assertThat(service.overview(root).weakPrerequisites()).isEmpty();
    }

    // ── T-C11 concept-layer projection (the "12 skipped required-practical
    //    edges" fix) ──────────────────────────────────────────────────────

    private com.syllabai.knowledge.KnowledgeEdge anchorEdge(UUID conceptId, String conceptCode,
                                                             UUID spId) {
        com.syllabai.knowledge.KnowledgeNode concept =
                mock(com.syllabai.knowledge.KnowledgeNode.class);
        when(concept.id()).thenReturn(conceptId);
        when(concept.code()).thenReturn(conceptCode);
        com.syllabai.knowledge.KnowledgeEdge edge =
                mock(com.syllabai.knowledge.KnowledgeEdge.class);
        when(edge.sourceId()).thenReturn(conceptId);
        when(edge.targetId()).thenReturn(spId);
        when(edge.source()).thenReturn(concept);
        return edge;
    }

    @Test
    @DisplayName("T-C11 projection: concept prerequisite reaches the class surface via its "
            + "anchor SP, flagged derived with the concept codes; unmeasured anchor never claims")
    void conceptPrerequisitesProjectThroughAnchors() {
        givenTree();
        givenNoEvidence();
        // the settled-store shape: practical B requires CON-SOLUBILITY, whose
        // validated anchor places the concept on topicA (SP 1.1). The class
        // measures topicA weak — the projected pair (topicA → practicalB) must
        // surface, marked derived, even though the concept itself is never
        // measured.
        var anchors = List.of(anchorEdge(conceptX, "4CH1-CON-SOLUBILITY", topicA));
        when(graph.conceptAnchorsWithin(root)).thenReturn(anchors);
        when(graph.prerequisiteRelations(root)).thenReturn(List.of(
                new KnowledgeGraphService.PrerequisiteRelation(conceptX, practicalB)));
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.1, 4),
                skill(learner2, topicA, 0.3, 4),
                skill(learner1, practicalB, 0.6, 2)));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One"), user(learner2, "Beta Two")));

        var weak = service.overview(root).weakPrerequisites();
        assertThat(weak).hasSize(1);
        var view = weak.get(0);
        assertThat(view.prerequisiteNodeId()).isEqualTo(topicA);
        assertThat(view.prerequisiteCode()).isEqualTo("4CH1-1.1");
        assertThat(view.meanMastery()).isEqualTo(0.2);
        assertThat(view.derived()).isTrue();
        assertThat(view.derivedViaConceptCodes()).containsExactly("4CH1-CON-SOLUBILITY");
        assertThat(view.dependents()).hasSize(1);
        assertThat(view.dependents().get(0).nodeId()).isEqualTo(practicalB);
        assertThat(view.dependents().get(0).code()).isEqualTo("4CH1-PR-01");
        assertThat(view.dependents().get(0).meanMastery()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("T-C11 projection honesty: self-projection collapses; direct structure "
            + "prerequisites pass through unflagged")
    void projectionCollapsesSelfAndKeepsDirectPairs() {
        givenTree();
        givenNoEvidence();
        // (1) the concept's prerequisite points at the very SP that anchors it
        //     (topicA depends on CON-SOLUBILITY, anchored on topicA) — a node
        //     is not its own prerequisite: nothing surfaces.
        // (2) a direct structure-level prerequisite (topicA → topicB) passes
        //     through WITHOUT the derived flag.
        var anchors = List.of(anchorEdge(conceptX, "4CH1-CON-SOLUBILITY", topicA));
        when(graph.conceptAnchorsWithin(root)).thenReturn(anchors);
        when(graph.prerequisiteRelations(root)).thenReturn(List.of(
                new KnowledgeGraphService.PrerequisiteRelation(conceptX, topicA),
                new KnowledgeGraphService.PrerequisiteRelation(topicA, topicB)));
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.2, 4),
                skill(learner1, topicB, 0.7, 2)));
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of(user(learner1, "Alpha One")));

        var weak = service.overview(root).weakPrerequisites();
        assertThat(weak).hasSize(1);
        assertThat(weak.get(0).prerequisiteNodeId()).isEqualTo(topicA);
        assertThat(weak.get(0).derived()).isFalse();
        assertThat(weak.get(0).derivedViaConceptCodes()).isEmpty();
        assertThat(weak.get(0).dependents()).hasSize(1);
        assertThat(weak.get(0).dependents().get(0).nodeId()).isEqualTo(topicB);
    }

    // ── drill-down ──────────────────────────────────────────────────────

    @Test
    @DisplayName("drill-down: weak + misconception learners with reasons; evidence + servable questions; subject isolation 404")
    void drillDownAffectedLearnersAndIsolation() {
        givenTree();
        givenNoEvidence();
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.2, 3),     // weak mastery (floor met)
                skill(learner2, topicA, 0.7, 1)));   // 1 attempt < floor → not weak-claimed
        when(misconceptionStates.findByMisconceptionNodeIdIn(anyCollection()))
                .thenReturn(List.of(misco(learner2, miscoA, 0.8)));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One"), user(learner2, "Beta Two")));
        when(users.findAllById(anyCollection())).thenReturn(List.of(
                user(learner1, "Alpha One"), user(learner2, "Beta Two")));
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                new StudentQuestionView(UUID.randomUUID(), "q1", "STRUCTURED", "stem", 5, 2,
                        300, "command", topicA, null, List.of(), List.of(), List.of(),
                        List.of())));
        when(graph.prerequisiteChain(topicA)).thenReturn(List.of(
                new PrerequisiteView(topicB, "4CH1-1.2", "TOPIC", "Moles", 1)));

        var dd = service.topicDrillDown(root, topicA);
        assertThat(dd.topic().nodeId()).isEqualTo(topicA);
        assertThat(dd.prerequisiteChain()).hasSize(1);
        assertThat(dd.prerequisiteChain().get(0).code()).isEqualTo("4CH1-1.2");
        assertThat(dd.prerequisiteChain().get(0).masteryBand()).isEqualTo("UNMEASURED");
        assertThat(dd.servableQuestions()).hasSize(1);

        var affected = dd.affectedLearners();
        var a1 = affected.stream().filter(a -> a.learnerId().equals(learner1))
                .findFirst().orElseThrow();
        assertThat(a1.reason()).isEqualTo("LOW_MASTERY");
        assertThat(a1.mastery()).isEqualTo(0.2);
        var a2 = affected.stream().filter(a -> a.learnerId().equals(learner2))
                .findFirst().orElseThrow();
        // learner2: below the evidence floor for mastery claims, but ACTIVE misconception
        assertThat(a2.reason()).isEqualTo("ACTIVE_MISCONCEPTION");
        assertThat(a2.mastery()).isNull();
        assertThat(a2.misconceptions()).hasSize(1);

        // subject isolation: a topic from another subject is a 404
        assertThatThrownBy(() -> service.topicDrillDown(root, outside))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("deterministic learner order: measured-weakest first, unmeasured last by name")
    void deterministicLearnerOrder() {
        givenTree();
        givenNoEvidence();
        when(skillStates.findByNodeIdIn(anyCollection())).thenReturn(List.of(
                skill(learner1, topicA, 0.6, 3),
                skill(learner2, topicA, 0.2, 3)));
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of(
                user(learner3, "Aaa First"),      // unmeasured — must NOT be first
                user(learner1, "Late Name"),
                user(learner2, "Early Name")));

        var rows = service.learners(root);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).learnerId()).isEqualTo(learner2);   // weakest measured
        assertThat(rows.get(1).learnerId()).isEqualTo(learner1);
        assertThat(rows.get(2).evidenceState()).isEqualTo("UNMEASURED");
        assertThat(rows.get(2).displayName()).isEqualTo("Aaa First");
    }

    @Test
    @DisplayName("recent activity aggregates raw attempts in the window, per learner")
    void recentActivityWindow() {
        givenTree();
        givenNoEvidence();
        when(attempts.aggregateByLearnerSinceWithin(any(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{learner1, 5L, 2L, T0}));
        when(users.findEnabledByRole(Role.STUDENT))
                .thenReturn(List.of(user(learner1, "Alpha One")));

        var overview = service.overview(root);
        assertThat(overview.recentActivity().recentAttempts()).isEqualTo(5);
        assertThat(overview.recentActivity().learnersActive()).isEqualTo(1);
        assertThat(overview.learnersRecentlyActive()).isEqualTo(1);
        assertThat(overview.recentActivity().windowStart())
                .isAfter(Instant.now().minusSeconds(20 * 3600 * 24));

        var row = service.learners(root).get(0);
        assertThat(row.recentAttempts()).isEqualTo(5);
        assertThat(row.recentCorrect()).isEqualTo(2);
        assertThat(row.lastActivityAt()).isEqualTo(T0);
    }
}
