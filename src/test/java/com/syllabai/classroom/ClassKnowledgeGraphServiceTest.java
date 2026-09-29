package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassGraphEdgeView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassGraphNodeView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassKnowledgeGraphView;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the F-072 class-KG heatmap aggregation: THE
 * INDEPENDENT-STUDENT RULE (the roster query is exactly the enabled member
 * rows — a non-member never enters any number), the §13.3 distribution
 * (mean + struggling/developing/proficient by the shared band vocabulary,
 * decayed at read time), the §13.4 coverage fusion (spec points verbatim,
 * topics derived from descendant rows with counts exposed), honest
 * unmeasured cells, subject-scope edge filtering, and the 404 propagation
 * for an unknown root.
 */
class ClassKnowledgeGraphServiceTest {

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final MisconceptionStateRepository misconceptionStates =
            mock(MisconceptionStateRepository.class);
    private final TeachingCoverageRepository coverage = mock(TeachingCoverageRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private final LearnerProperties learnerProperties = new LearnerProperties(null, null, null, null);
    private final EbbinghausDecayService decayService = new EbbinghausDecayService();

    private final ClassKnowledgeGraphService service = new ClassKnowledgeGraphService(
            graph, skillStates, misconceptionStates, coverage, members, users,
            decayService, learnerProperties);

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();

    private final UUID root = UUID.randomUUID();
    private final UUID unit = UUID.randomUUID();
    private final UUID topicA = UUID.randomUUID();   // 4CH1-1.1 Atoms
    private final UUID specA1 = UUID.randomUUID();   // 4CH1-1.1a — recorded TAUGHT
    private final UUID specA2 = UUID.randomUUID();   // 4CH1-1.1b — unrecorded
    private final UUID miscoA = UUID.randomUUID();   // misconception under topicA
    private final UUID topicB = UUID.randomUUID();   // 4CH1-1.2 Moles
    private final UUID specB1 = UUID.randomUUID();   // 4CH1-1.2a — recorded NOT_TAUGHT
    private final UUID topicC = UUID.randomUUID();   // 4CH1-1.3 — no spec points, never measured
    private final UUID outside = UUID.randomUUID();  // another subject's node

    private final UUID learnerA = UUID.randomUUID(); // enabled member
    private final UUID learnerB = UUID.randomUUID(); // enabled member
    private final UUID learnerC = UUID.randomUUID(); // DISABLED member (roster rule)
    private final UUID independent = UUID.randomUUID(); // NO membership row anywhere

    /** fresh practice: the decay read returns the stored mastery unchanged */
    private static final Instant FRESH = Instant.now();
    /** stale practice: paper-default decay drives effective mastery to the floor */
    private static final Instant ANCIENT = Instant.parse("2020-01-01T00:00:00Z");

    // ── fixtures ────────────────────────────────────────────────────────

    private NodeView node(UUID id, String code, String type, String title,
                          Map<String, Object> applicability, List<NodeView> children) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null,
                applicability, children);
    }

    private NodeView specPoint(UUID id, String code) {
        return node(id, code, "SUBTOPIC", code + " detail",
                Map.of("papers", List.of("1C", "2C")), List.of());
    }

    /** the 4CH1-shaped tree: root → unit → topics (A: 2 spec points + misco, B: 1, C: none) */
    private NodeView subjectTree() {
        return node(root, "4CH1", "SUBJECT", "Edexcel IGCSE Chemistry 4CH1", null, List.of(
                node(unit, "4CH1-S1", "UNIT", "Section 1", null, List.of(
                        node(topicA, "4CH1-1.1", "TOPIC", "Atoms", null, List.of(
                                specPoint(specA1, "4CH1-1.1a"),
                                specPoint(specA2, "4CH1-1.1b"),
                                node(miscoA, "MIS-A", "MISCONCEPTION", "Atoms are lost",
                                        null, List.of()))),
                        node(topicB, "4CH1-1.2", "TOPIC", "Moles", null, List.of(
                                specPoint(specB1, "4CH1-1.2a"))),
                        node(topicC, "4CH1-1.3", "TOPIC", "Bonding", null, List.of())))));
    }

    private User user(UUID id, String name, boolean enabled) {
        User u = mock(User.class);
        when(u.id()).thenReturn(id);
        when(u.enabled()).thenReturn(enabled);
        when(u.displayName()).thenReturn(name);
        return u;
    }

    private SkillState skill(UUID learner, UUID node, double mastery, Instant practicedAt,
                             int attempts, int correct) {
        SkillState s = new SkillState(learner, node, mastery, practicedAt);
        for (int i = 0; i < attempts; i++) {
            s.recordAttempt(i < correct, mastery, practicedAt);
        }
        return s;
    }

    private MisconceptionState misco(UUID learner, UUID node, double probability) {
        MisconceptionState m = new MisconceptionState(learner, node, probability, FRESH);
        m.update(probability, FRESH);
        return m;
    }

    /** the base wiring: A + B enabled members, C disabled member, D independent */
    private void givenClassRoster() {
        // build stub results BEFORE the when() calls — Mockito forbids nested stubbing
        List<ClassMember> rosterRows = List.of(
                member(learnerA), member(learnerB), member(learnerC));
        List<User> rosterUsers = List.of(
                user(learnerA, "Alice", true),
                user(learnerB, "Bob", true),
                user(learnerC, "Carol", false));
        when(members.findByClassIdOrderByEnrolledAtAsc(CLASS_ID)).thenReturn(rosterRows);
        when(users.findAllById(anyCollection())).thenReturn(rosterUsers);
    }

    private ClassMember member(UUID studentId) {
        ClassMember m = mock(ClassMember.class);
        when(m.studentId()).thenReturn(studentId);
        return m;
    }

    private void givenEmptyEvidence() {
        when(skillStates.findByLearnerIdInAndNodeIdIn(anyCollection(), anyCollection()))
                .thenReturn(List.of());
        when(misconceptionStates.findByLearnerIdInAndMisconceptionNodeIdIn(
                anyCollection(), anyCollection())).thenReturn(List.of());
        when(coverage.findByClassIdOrderBySpecPointNodeIdAsc(CLASS_ID)).thenReturn(List.of());
        when(graph.prerequisiteRelations(root)).thenReturn(List.of());
    }

    private ClassKnowledgeGraphView build() {
        return service.graph(liveClass(), root);
    }

    private SchoolClass liveClass() {
        return new SchoolClass(TEACHER, "igcse-chemistry-19", "IGCSE Chemistry", "10A") {
            @Override
            public UUID id() {
                return CLASS_ID;
            }
        };
    }

    private Map<UUID, ClassGraphNodeView> byId(ClassKnowledgeGraphView view) {
        Map<UUID, ClassGraphNodeView> out = new java.util.HashMap<>();
        for (ClassGraphNodeView n : view.nodes()) {
            out.put(n.id(), n);
        }
        return out;
    }

    // ── the independent-student rule + roster gates ─────────────────────

    @Test
    @DisplayName("THE INDEPENDENT-STUDENT RULE: the roster query is exactly the ENABLED member rows — a disabled member drops out and an independent student is never queried")
    void rosterIsExactlyEnabledMembers() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
        givenClassRoster();
        givenEmptyEvidence();

        ClassKnowledgeGraphView view = build();

        assertThat(view.learnersEnrolled()).isEqualTo(2);
        ArgumentCaptor<Collection<UUID>> rosterCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(skillStates).findByLearnerIdInAndNodeIdIn(rosterCaptor.capture(), anyCollection());
        assertThat(rosterCaptor.getValue())
                .containsExactlyInAnyOrder(learnerA, learnerB);
        assertThat(rosterCaptor.getValue())
                .doesNotContain(learnerC, independent);
        ArgumentCaptor<Collection<UUID>> miscoRosterCaptor =
                ArgumentCaptor.forClass(Collection.class);
        verify(misconceptionStates).findByLearnerIdInAndMisconceptionNodeIdIn(
                miscoRosterCaptor.capture(), anyCollection());
        assertThat(miscoRosterCaptor.getValue())
                .containsExactlyInAnyOrder(learnerA, learnerB);
    }

    // ── §13.3 aggregation semantics ─────────────────────────────────────

    @Test
    @DisplayName("§13.3 distribution: mean + struggling/developing/proficient by the shared bands over EFFECTIVE mastery, with evidence counts")
    void distributionOverEffectiveMastery() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
        givenClassRoster();
        when(skillStates.findByLearnerIdInAndNodeIdIn(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        skill(learnerA, specA1, 0.9, FRESH, 4, 4),   // SECURE
                        skill(learnerB, specA1, 0.5, FRESH, 3, 1),   // DEVELOPING
                        skill(learnerA, specA2, 0.3, FRESH, 2, 0),   // LOW
                        // stored 0.9 but practiced in 2020 → decayed to the 0.1 floor → LOW
                        skill(learnerB, specB1, 0.9, ANCIENT, 5, 5)));
        when(misconceptionStates.findByLearnerIdInAndMisconceptionNodeIdIn(
                anyCollection(), anyCollection())).thenReturn(List.of(
                misco(learnerA, miscoA, 0.7),   // active (>= 0.5)
                misco(learnerB, miscoA, 0.3))); // inactive
        when(coverage.findByClassIdOrderBySpecPointNodeIdAsc(CLASS_ID)).thenReturn(List.of());
        when(graph.prerequisiteRelations(root)).thenReturn(List.of());

        Map<UUID, ClassGraphNodeView> nodes = byId(build());

        ClassGraphNodeView a1 = nodes.get(specA1);
        assertThat(a1.learnersMeasured()).isEqualTo(2);
        assertThat(a1.meanMastery()).isEqualTo(0.7);            // (0.9 + 0.5) / 2
        assertThat(a1.meanBand()).isEqualTo("DEVELOPING");
        assertThat(a1.proficientCount()).isEqualTo(1);
        assertThat(a1.developingCount()).isEqualTo(1);
        assertThat(a1.strugglingCount()).isZero();
        assertThat(a1.attempts()).isEqualTo(7);
        assertThat(a1.correctCount()).isEqualTo(5);

        ClassGraphNodeView a2 = nodes.get(specA2);
        assertThat(a2.meanMastery()).isEqualTo(0.3);
        assertThat(a2.meanBand()).isEqualTo("LOW");
        assertThat(a2.strugglingCount()).isEqualTo(1);

        // the decay pin: stored 0.9, effective floored to 0.1 — the cell shows
        // CURRENT class understanding, not the day-of-practice snapshot
        ClassGraphNodeView b1 = nodes.get(specB1);
        assertThat(b1.meanMastery()).isEqualTo(0.1);
        assertThat(b1.meanBand()).isEqualTo("LOW");
        assertThat(b1.strugglingCount()).isEqualTo(1);

        // misconception prevalence: distinct ACTIVE members under topicA = 1
        // (B's 0.3 estimate is below the BDT threshold and must not count)
        ClassGraphNodeView tA = nodes.get(topicA);
        assertThat(tA.learnersWithActiveMisconception()).isEqualTo(1);
    }

    @Test
    @DisplayName("honest unmeasured: no states → learnersMeasured 0, null mean, UNMEASURED band, zero counts — nothing fabricated")
    void honestUnmeasuredCells() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
        givenClassRoster();
        givenEmptyEvidence();

        Map<UUID, ClassGraphNodeView> nodes = byId(build());

        for (UUID id : List.of(topicA, topicB, topicC, unit, root)) {
            ClassGraphNodeView n = nodes.get(id);
            assertThat(n.learnersMeasured()).as("measured on %s", n.code()).isZero();
            assertThat(n.meanMastery()).as("mean on %s", n.code()).isNull();
            assertThat(n.meanBand()).as("band on %s", n.code()).isEqualTo("UNMEASURED");
            assertThat(n.strugglingCount()).isZero();
            assertThat(n.developingCount()).isZero();
            assertThat(n.proficientCount()).isZero();
            assertThat(n.attempts()).isZero();
            assertThat(n.correctCount()).isZero();
            assertThat(n.learnersWithActiveMisconception()).isZero();
        }
    }

    // ── §13.4 coverage fusion ───────────────────────────────────────────

    @Test
    @DisplayName("§13.4 coverage fusion: spec points verbatim from their recorded row, topics derived from descendant rows with counts exposed")
    void coverageFusion() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
        givenClassRoster();
        when(skillStates.findByLearnerIdInAndNodeIdIn(anyCollection(), anyCollection()))
                .thenReturn(List.of());
        when(misconceptionStates.findByLearnerIdInAndMisconceptionNodeIdIn(
                anyCollection(), anyCollection())).thenReturn(List.of());
        when(coverage.findByClassIdOrderBySpecPointNodeIdAsc(CLASS_ID))
                .thenReturn(List.of(
                        new TeachingCoverage(CLASS_ID, specA1,
                                TeachingCoverage.Status.TAUGHT, TEACHER, null),
                        new TeachingCoverage(CLASS_ID, specB1,
                                TeachingCoverage.Status.NOT_TAUGHT, TEACHER, null)));
        when(graph.prerequisiteRelations(root)).thenReturn(List.of());

        Map<UUID, ClassGraphNodeView> nodes = byId(build());

        // spec points report their own row verbatim (or the honest absence)
        assertThat(nodes.get(specA1).coverageState()).isEqualTo("taught");
        assertThat(nodes.get(specA1).specPoints()).isEqualTo(1);
        assertThat(nodes.get(specA1).recordedSpecPoints()).isEqualTo(1);
        assertThat(nodes.get(specA1).taughtSpecPoints()).isEqualTo(1);
        assertThat(nodes.get(specA2).coverageState()).isEqualTo("unrecorded");
        assertThat(nodes.get(specA2).recordedSpecPoints()).isZero();
        assertThat(nodes.get(specB1).coverageState()).isEqualTo("not-taught");
        assertThat(nodes.get(specB1).taughtSpecPoints()).isZero();

        // topics derive: topicA has 2 spec points, 1 recorded taught → taught
        assertThat(nodes.get(topicA).coverageState()).isEqualTo("taught");
        assertThat(nodes.get(topicA).specPoints()).isEqualTo(2);
        assertThat(nodes.get(topicA).recordedSpecPoints()).isEqualTo(1);
        assertThat(nodes.get(topicA).taughtSpecPoints()).isEqualTo(1);

        // topicB: 1 spec point recorded NOT_TAUGHT → not-taught (taught-but-weak
        // and not-yet-taught stay distinguishable — the whole point of the matrix)
        assertThat(nodes.get(topicB).coverageState()).isEqualTo("not-taught");

        // topicC has no spec points at all → unrecorded, honest absence
        assertThat(nodes.get(topicC).coverageState()).isEqualTo("unrecorded");
        assertThat(nodes.get(topicC).specPoints()).isZero();

        // root rolls the whole subtree up
        assertThat(nodes.get(root).specPoints()).isEqualTo(3);
        assertThat(nodes.get(root).recordedSpecPoints()).isEqualTo(2);
        assertThat(nodes.get(root).taughtSpecPoints()).isEqualTo(1);
        assertThat(nodes.get(root).coverageState()).isEqualTo("taught");
    }

    // ── topology + scope ────────────────────────────────────────────────

    @Test
    @DisplayName("topology: curriculum-order nodes with childIds, prerequisite edges kept only inside the subject scope, codes from the node registry")
    void topologyAndScope() {
        when(graph.treeWithMisconceptions(root)).thenReturn(subjectTree());
        givenClassRoster();
        givenEmptyEvidence();
        when(graph.prerequisiteRelations(root)).thenReturn(List.of(
                new KnowledgeGraphService.PrerequisiteRelation(specA1, specA2),
                new KnowledgeGraphService.PrerequisiteRelation(outside, specB1)));

        ClassKnowledgeGraphView view = build();

        assertThat(view.rootId()).isEqualTo(root);
        assertThat(view.rootCode()).isEqualTo("4CH1");
        assertThat(view.className()).isEqualTo("10A");
        // curriculum order: the tree walked pre-order, misconception excluded
        assertThat(view.nodes()).extracting(ClassGraphNodeView::id)
                .containsExactly(root, unit, topicA, specA1, specA2, topicB, specB1, topicC);
        assertThat(view.nodes().get(2).childIds()).containsExactly(specA1, specA2);
        assertThat(view.nodes().get(2).title()).isEqualTo("Atoms");
        // the outside-subject relation is skipped, never guessed
        assertThat(view.prerequisiteEdges()).hasSize(1);
        ClassGraphEdgeView edge = view.prerequisiteEdges().get(0);
        assertThat(edge.prerequisiteId()).isEqualTo(specA1);
        assertThat(edge.prerequisiteCode()).isEqualTo("4CH1-1.1a");
        assertThat(edge.nodeId()).isEqualTo(specA2);
        assertThat(edge.nodeCode()).isEqualTo("4CH1-1.1b");
    }

    @Test
    @DisplayName("unknown root propagates 404 (subject isolation reuses the graph read service)")
    void unknownRootIs404() {
        givenClassRoster();
        givenEmptyEvidence();
        when(graph.treeWithMisconceptions(root))
                .thenThrow(new NotFoundException("knowledge node", root));

        assertThatThrownBy(this::build).isInstanceOf(NotFoundException.class);
    }
}
