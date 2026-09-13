package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
import com.syllabai.teacher.ConceptGraphSeedService;
import com.syllabai.teacher.TeacherConceptGraphController;
import com.syllabai.teacher.TeacherConceptGraphController.ConceptGraphEdgesView;
import com.syllabai.teacher.TeacherConceptGraphController.ConceptGraphEdgeView;
import com.syllabai.assessment.ServableQuestionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the teacher-side 4CH1 concept-graph seed against a real
 * Postgres with the production beans (V15, session 56). The full product path
 * under test: teacher activates the pinned snapshot → the real curriculum
 * rows (4 sections / 28 subsections / 182 spec points / 12 practicals) + the
 * settled T-C11 graph (113 nodes / 117 anchors / 153 validated edges) land in
 * the existing KG → the teacher read surfaces express them (tree +
 * graph-derived edges) → the learner NBA loop activates against the seeded
 * codes automatically (session 55's join contract) — and re-activation is a
 * structural no-op (idempotency), with learner state provably never leaking
 * into the teacher KG read models.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ConceptGraphSeedFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private ConceptGraphSeedService seed;
    @Autowired
    private TeacherConceptGraphController teacherGraph;
    @Autowired
    private KnowledgeGraphService graph;
    @Autowired
    private KnowledgeNodeRepository nodes;
    @Autowired
    private SkillStateRepository skillStates;
    @Autowired
    private MisconceptionStateRepository misconceptionStates;
    @Autowired
    private AuthService authService;
    @Autowired
    private NextBestActionService nextBestActions;
    @Autowired
    private ServableQuestionService servableQuestions;
    @Autowired
    private KnowledgeGraphService graphService;

    @Test
    @DisplayName("Teacher → 4CH1 → spec point → concept graph: real rows, real edges, "
            + "idempotent re-run, learner activation, student isolation")
    void fullTeacherConceptGraphPath() {
        // 1. ACTIVATION — the deterministic seed of the pinned snapshots
        ConceptGraphSeedService.SeedSummary first = seed.activate(UUID.randomUUID());
        assertThat(first.sections()).isEqualTo(4);
        assertThat(first.subsections()).isEqualTo(28);
        assertThat(first.specPoints()).isEqualTo(182);
        assertThat(first.practicals()).isEqualTo(12);
        assertThat(first.conceptNodes()).isEqualTo(113);
        assertThat(first.validatedSemanticEdges()).isEqualTo(153);
        assertThat(first.nodesCreated()).isEqualTo(1 + 4 + 28 + 182 + 12 + 113);
        assertThat(first.edgesCreated()).isEqualTo(4 + 28 + 182 + 12 + 117 + 153);

        // 2. THE PRODUCT PATH — teacher selects 4CH1 → section → spec point:
        // the real SP 4CH1-3.7C (bond-energy calculations) carries its concepts
        KnowledgeNode sp = nodes.findByCode("4CH1-3.7C").orElseThrow();
        NodeView tree = graph.treeWithMisconceptions(first.rootNodeId());
        NodeView sectionS3 = findChild(tree, "4CH1-S3");
        NodeView subsection = findChild(sectionS3, "4CH1-S3-a");   // the SP's real subsection
        NodeView specPoint = findDeep(subsection, "4CH1-3.7C");
        assertThat(specPoint.id()).isEqualTo(sp.id());
        assertThat(specPoint.validationStatus()).isEqualTo("VALIDATED");    // official anchor
        // the graph-derived concept hangs under the official anchor, honestly SUGGESTED
        NodeView bondEnergyCalc = findDeep(specPoint, "4CH1-CON-BOND-ENERGY-CALC");
        assertThat(bondEnergyCalc.type()).isEqualTo("CONCEPT");
        assertThat(bondEnergyCalc.validationStatus()).isEqualTo("SUGGESTED");
        assertThat(bondEnergyCalc.provenance()).contains("t-c11:settled");
        // …and the misconception folds under the concept (REMEDIATED_BY — no
        // MISCONCEPTION_OF edge exists for it in the settled store)
        NodeView mis = findDeep(bondEnergyCalc, "4CH1-MIS-BOND-ENERGY-COUNT");
        assertThat(mis.type()).isEqualTo("MISCONCEPTION");

        // 3. THE VALIDATED RELATIONSHIPS — the teacher edge read model
        ConceptGraphEdgesView edgeView = teacherGraph.edges(first.rootNodeId());
        assertThat(edgeView.edges()).hasSize(153);
        ConceptGraphEdgeView prerequisite = edgeView.edges().stream()
                .filter(e -> "REQUIRES_PREREQUISITE".equals(e.relation())
                        && "4CH1-CON-BOND-ENERGY-CALC".equals(e.source().code())
                        && "4CH1-CON-COVALENT-BOND".equals(e.target().code()))
                .findFirst().orElseThrow();
        assertThat(prerequisite.validationStatus()).isEqualTo("VALIDATED");
        assertThat(prerequisite.provenance()).contains("validated_by:operator");
        assertThat(edgeView.edges()).allSatisfy(e -> {
            assertThat(e.relation()).isNotEqualTo("PART_OF");   // structure stays the tree's job
            assertThat(e.provenance()).contains("t-c11:settled");
        });
        // deterministic ordering (relation, source code, target code)
        assertThat(edgeView.edges()).isSortedAccordingTo(
                java.util.Comparator.comparing(ConceptGraphEdgeView::relation)
                        .thenComparing(e -> e.source().code())
                        .thenComparing(e -> e.target().code()));

        // 4. LEARNER ACTIVATION (session 55's join, against the seeded rows):
        // real BKT/BDT evidence on the seeded nodes → graph-informed actions
        UUID becId = nodes.findByCode("4CH1-CON-BOND-ENERGY-CALC").orElseThrow().id();
        UUID misId = nodes.findByCode("4CH1-MIS-BOND-ENERGY-COUNT").orElseThrow().id();
        UUID learner = newLearner();
        Instant practised = Instant.now().minusSeconds(3600);
        SkillState weak = new SkillState(learner, becId, 0.20, practised);
        for (int i = 0; i < 3; i++) {
            weak.recordAttempt(false, 0.20, practised);
        }
        skillStates.save(weak);
        MisconceptionState active = new MisconceptionState(learner, misId, 0.4, practised);
        active.update(0.75, practised);
        misconceptionStates.save(active);

        NextBestActionsView actions = nextBestActions.actionsFor(learner, first.rootNodeId());
        assertThat(actions.policy()).isEqualTo("nba-rules/v1.1");
        assertThat(actions.actions()).anySatisfy(a -> {
            assertThat(a.actionType()).isEqualTo(ActionType.REVIEW_PREREQUISITE);
            assertThat(a.reasonCode()).isEqualTo(ReasonCode.VALIDATED_PREREQUISITE_CHAIN);
            assertThat(a.targetNodeId()).isEqualTo(
                    nodes.findByCode("4CH1-CON-COVALENT-BOND").orElseThrow().id());
        });
        assertThat(actions.actions()).anySatisfy(a -> {
            assertThat(a.actionType()).isEqualTo(ActionType.REMEDIATE_MISCONCEPTION);
            assertThat(a.reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_REMEDIATION);
            assertThat(a.targetNodeId()).isEqualTo(becId);
        });

        // 5. STUDENT ISOLATION — learner evidence never becomes teacher KG data:
        // the teacher edge read model is byte-stable before/after learner state
        assertThat(teacherGraph.edges(first.rootNodeId()).edges())
                .containsExactlyElementsOf(edgeView.edges());
        UUID otherLearner = newLearner();
        assertThat(nextBestActions.actionsFor(otherLearner, first.rootNodeId()).actions())
                .isEmpty();   // the graph alone never acts

        // 6. IDEMPOTENCY — re-running the seed is a structural no-op
        ConceptGraphSeedService.SeedSummary second = seed.activate(UUID.randomUUID());
        assertThat(second.nodesCreated()).isZero();
        assertThat(second.edgesCreated()).isZero();
        assertThat(second.alreadyActive()).isTrue();
        assertThat(second.rootNodeId()).isEqualTo(first.rootNodeId());
        assertThat(teacherGraph.edges(first.rootNodeId()).edges())
                .containsExactlyElementsOf(edgeView.edges());
        assertThat(nextBestActions.actionsFor(learner, first.rootNodeId()).actions())
                .containsExactlyElementsOf(actions.actions());
    }

    // ── helpers ────────────────────────────────────────────────────

    private NodeView findChild(NodeView parent, String code) {
        return parent.children().stream()
                .filter(c -> code.equals(c.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "node " + code + " not a direct child of " + parent.code()));
    }

    private NodeView findDeep(NodeView parent, String code) {
        if (code.equals(parent.code())) {
            return parent;
        }
        for (NodeView child : parent.children()) {
            try {
                return findDeep(child, code);
            } catch (AssertionError ignored) {
                // keep searching siblings
            }
        }
        throw new AssertionError("node " + code + " not found under " + parent.code());
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-seed-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("Subject-scoped practice: the 4CH1 surface serves zero questions, "
            + "the V6 IAL surface keeps its 8 MCQs (pilot-readiness session-56)")
    void practiceIsSubjectScoped() {
        // the 4CH1 seed and the V6 IAL seed coexist in one database — exactly
        // the production shape after a teacher activates 4CH1
        seed.activate(UUID.randomUUID());

        KnowledgeNode root4ch1 = nodes.findByCode("4CH1").orElseThrow();
        KnowledgeNode rootWch11 = nodes.findByCode("CHM").orElseThrow();

        // the demo MCQs map under the IAL subtree only — the 4CH1-scoped list
        // must be honestly empty (no cross-subject practice under 4CH1)
        assertThat(servableQuestions.activeWithin(
                graphService.subtreeIds(root4ch1.id()))).isEmpty();

        // and the IAL subject keeps serving its 8 seeded MCQs
        assertThat(servableQuestions.activeWithin(
                graphService.subtreeIds(rootWch11.id()))).hasSize(8);
    }
}
