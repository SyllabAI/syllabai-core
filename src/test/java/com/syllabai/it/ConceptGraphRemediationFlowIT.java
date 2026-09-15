package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.KnowledgeNode.ValidationStatus;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.recommendation.ConceptDependencyGraph;
import com.syllabai.recommendation.LearnerRecommendationController;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
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
 * Integration test: the settled T-C11 concept graph wired into the learner
 * next-best-action loop against a real Postgres (v1.1, ADR-017). The full
 * chain under test: KG rows whose <em>codes</em> carry the settled store's
 * concept identities + real learner-state rows (BKT/BDT) + the packaged
 * SHA-256-pinned snapshot bean (113 nodes / 153 HUMAN_VALIDATED semantic
 * edges, loaded by the production graph configuration) — ranked
 * evidence-backed actions out.
 *
 * <p>The seeded subtree uses the store's real codes and the real validated
 * edges of its slice: {@code CON-BOND-ENERGY-CALC -REQUIRES_PREREQUISITE->
 * CON-COVALENT-BOND} and {@code MIS-BOND-ENERGY-COUNT -REMEDIATED_BY->
 * CON-BOND-ENERGY-CALC}. The graph layer must nominate the prerequisite
 * remediation and the misconception correction from the learner's measured
 * evidence, while the KG's own curriculum structure (PART_OF,
 * MISCONCEPTION_OF) stays authoritative — exactly the integration contract:
 * graph informs, evidence gates, structure anchors.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ConceptGraphRemediationFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private KnowledgeNodeRepository nodes;
    @Autowired
    private KnowledgeEdgeRepository edges;
    @Autowired
    private SkillStateRepository skillStates;
    @Autowired
    private MisconceptionStateRepository misconceptionStates;
    @Autowired
    private AuthService authService;
    @Autowired
    private NextBestActionService nextBestActions;
    @Autowired
    private LearnerRecommendationController recommendationController;
    @Autowired
    private ConceptDependencyGraph conceptGraph;

    @Test
    @DisplayName("settled graph + real KG rows + real learner state → prerequisite-chain and misconception-remediation actions; deterministic; controller parity")
    void graphInformedActionsFromRealEvidence() {
        // 0. the production bean loaded the settled snapshot — the whole premise
        assertThat(conceptGraph.validatedEdgeCount()).isEqualTo(153);

        // 1. a 4CH1-coded KG subtree: the join keys are the settled store's codes
        UUID root = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID bec = UUID.randomUUID();
        UUID cov = UUID.randomUUID();
        UUID mis = UUID.randomUUID();
        saveNode(root, "4CH1-IT-" + suffix(), NodeType.SUBJECT, "Edexcel IGCSE Chemistry (IT)");
        saveNode(unit, "4CH1-IT-S3-" + suffix(), NodeType.UNIT, "Section 3 Physical (IT)");
        saveNode(bec, "4CH1-CON-BOND-ENERGY-CALC", NodeType.TOPIC, "Bond energy calculations");
        saveNode(cov, "4CH1-CON-COVALENT-BOND", NodeType.TOPIC, "Covalent bond");
        saveNode(mis, "4CH1-MIS-BOND-ENERGY-COUNT", NodeType.MISCONCEPTION,
                "Counts every bond occurrence as one bond in bond-energy sums");
        saveEdge(unit, root, RelationType.PART_OF);
        saveEdge(bec, unit, RelationType.PART_OF);
        saveEdge(cov, unit, RelationType.PART_OF);
        saveEdge(mis, bec, RelationType.MISCONCEPTION_OF);   // KG structure stays authoritative

        // 2. real learner evidence: established-weak on the dependent concept,
        //    active BDT state on the misconception (the deterministic gates)
        UUID learner = newLearner();
        Instant practised = Instant.now().minusSeconds(3600);
        SkillState weak = new SkillState(learner, bec, 0.20, practised);
        for (int i = 0; i < 3; i++) {
            weak.recordAttempt(false, 0.20, practised);
        }
        skillStates.save(weak);
        MisconceptionState active = new MisconceptionState(learner, mis, 0.4, practised);
        active.update(0.75, practised);
        misconceptionStates.save(active);

        // 3. the graph-informed loop: prerequisite chain + corrective concept
        NextBestActionsView view = nextBestActions.actionsFor(learner, root);
        assertThat(view.policy()).isEqualTo("nba-rules/v1.3");
        assertThat(view.actions()).hasSize(3);
        assertThat(view.actions().get(0).actionType()).isEqualTo(ActionType.REVIEW_PREREQUISITE);
        assertThat(view.actions().get(0).reasonCode()).isEqualTo(ReasonCode.VALIDATED_PREREQUISITE_CHAIN);
        assertThat(view.actions().get(0).targetNodeId()).isEqualTo(cov);
        assertThat(view.actions().get(0).reasonDetail()).contains("4CH1-CON-BOND-ENERGY-CALC");
        assertThat(view.actions().get(1).actionType()).isEqualTo(ActionType.ASK_TUTOR);
        assertThat(view.actions().get(1).targetNodeId()).isEqualTo(mis);
        assertThat(view.actions().get(2).actionType()).isEqualTo(ActionType.REMEDIATE_MISCONCEPTION);
        assertThat(view.actions().get(2).reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_REMEDIATION);
        assertThat(view.actions().get(2).targetNodeId()).isEqualTo(bec);
        assertThat(view.actions().get(2).reasonDetail()).contains("0.75");

        // 4. deterministic: same learner state ⇒ identical ranked actions
        assertThat(nextBestActions.actionsFor(learner, root).actions())
                .containsExactlyElementsOf(view.actions());

        // 5. learner isolation: a second learner with zero evidence on the same
        //    graph and subtree gets nothing — graph edges are not learner state
        UUID other = newLearner();
        assertThat(nextBestActions.actionsFor(other, root).actions()).isEmpty();

        // 6. the controller wiring (GET /api/v1/learners/me/recommendations)
        assertThat(recommendationController.recommendations(learner, root).actions())
                .containsExactlyElementsOf(view.actions());
    }

    // ── fixtures ───────────────────────────────────────────────────

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-cg-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    private void saveNode(UUID id, String code, NodeType type, String title) {
        KnowledgeNode node = new KnowledgeNode(code, type, title,
                "it fixture — settled T-C11 concept identity", ValidationStatus.VALIDATED,
                "concept-graph-it", "it");
        setEntityId(node, id);
        nodes.save(node);
    }

    private void saveEdge(UUID source, UUID target, RelationType relation) {
        KnowledgeEdge edge = new KnowledgeEdge(
                nodes.findById(source).orElseThrow(),
                nodes.findById(target).orElseThrow(),
                relation, null, "it fixture structure", ValidationStatus.VALIDATED,
                "concept-graph-it", "it");
        edges.save(edge);
    }

    private static void setEntityId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test fixture cannot set id", e);
        }
    }
}
