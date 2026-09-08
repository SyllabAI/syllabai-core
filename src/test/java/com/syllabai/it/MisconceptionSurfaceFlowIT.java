package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeGraphRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.learner.LearnerKnowledgeGraphService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
import java.util.ArrayList;
import java.util.List;
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
 * Integration test: the found-in-the-browser regression on the MISCONCEPTION_OF
 * read path. The seed contract (V6) runs MISCONCEPTION_OF edges misconception →
 * topic (source = the misconception); the pre-fix repository query selected
 * edges FROM the topic and mapped their TARGET — both directions inverted, so
 * the seeded misconceptions never appeared on ANY read surface: the tree
 * ({@code includeMisconceptions=true}), {@code GET /nodes/{id}/misconceptions},
 * the personalized mastery map (F-034), and the NBA MISCONCEPTION_SUSPECTED
 * rule (T-033) — even though the BDT learner state itself was correct. Unit
 * tests mock the tree, which is exactly how this stayed CI-green while the live
 * loop was broken: this IT pins the real edge direction against real Postgres
 * with the V6/V7 seed, from repository query up to the ranked action.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class MisconceptionSurfaceFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** V6 seed: subject root CHM. */
    private static final UUID SUBJECT_ROOT =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    /** V6 seed: WCH11-T1.1 "Mole calculations and reacting masses". */
    private static final UUID TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V6 seed: MIS-T1.1-01 "Classic mole-concept confusion" attached to WCH11-T1.1. */
    private static final UUID MIS_T1_1_01 =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    /** V7 seed: SEED-WCH11-001 (mass of 0.25 mol CaCO3) — deterministic fixture. */
    private static final UUID SEED_MCQ =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: option A "0.25 g" — wrong and tagged with MIS-T1.1-01. */
    private static final UUID SEED_MCQ_WRONG_OPTION =
            UUID.fromString("41000000-0000-0000-0000-000000000001");

    @Autowired
    private KnowledgeGraphRepository graphRepository;
    @Autowired
    private KnowledgeGraphService graphService;
    @Autowired
    private LearnerKnowledgeGraphService learnerGraph;
    @Autowired
    private NextBestActionService nextBestActions;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private AuthService authService;

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-mis-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("misconceptions surface on repository, tree, mastery map and NBA after a real misconception-tagged attempt")
    void misconceptionSurfacesAcrossReadModels() {
        // 1. repository: the seeded misconception of WCH11-T1.1 is reachable through
        //    the MISCONCEPTION_OF edge pointing AT the topic (source = misconception)
        assertThat(graphRepository.findMisconceptions(TOPIC_T1_1))
                .singleElement()
                .satisfies(node -> assertThat(node.id()).isEqualTo(MIS_T1_1_01));

        // 2. tree: includeMisconceptions carries the MISCONCEPTION node as a flat
        //    child of its topic, inside the subject-subtree walk
        NodeView tree = graphService.treeWithMisconceptions(SUBJECT_ROOT);
        List<NodeView> flat = flatten(tree);
        assertThat(flat).anyMatch(n -> MIS_T1_1_01.equals(n.id()) && "MISCONCEPTION".equals(n.type()));
        NodeView topicNode = flat.stream()
                .filter(n -> TOPIC_T1_1.equals(n.id())).findFirst().orElseThrow();
        assertThat(topicNode.children())
                .anyMatch(c -> MIS_T1_1_01.equals(c.id()));

        // 3. live evidence: a wrong MCQ answer tagged with the misconception makes
        //    the BDT state active (0.75), and the T-033 NBA must surface the
        //    MISCONCEPTION_SUSPECTED action targeting that exact misconception node
        UUID learner = newLearner();
        assessment.submit(learner, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_WRONG_OPTION, 25_000L, 4, false, false));

        NextBestActionsView actions = nextBestActions.actionsFor(learner, SUBJECT_ROOT);
        assertThat(actions.actions())
                .anySatisfy(a -> {
                    assertThat(a.actionType()).isEqualTo(ActionType.ASK_TUTOR);
                    assertThat(a.reasonCode()).isEqualTo(ReasonCode.MISCONCEPTION_SUSPECTED);
                    assertThat(a.targetNodeId()).isEqualTo(MIS_T1_1_01);
                    assertThat(a.reasonDetail()).contains("0.75");       // measured probability
                    assertThat(a.reasonDetail()).contains("1 evidence"); // real evidence count
                    assertThat(a.reasonDetail()).contains("WCH11-T1.1"); // traceable parent topic
                });

        // 4. mastery map (F-034): the MISCONCEPTION node carries the learner's
        //    active annotation — the "misconception watch" leg of the dashboard
        LearnerKnowledgeGraphView view = learnerGraph.graphFor(learner, SUBJECT_ROOT);
        assertThat(view.nodes())
                .filteredOn(n -> MIS_T1_1_01.equals(n.id()))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.misconceptionProbability()).isEqualTo(0.75);
                    assertThat(n.misconceptionActive()).isTrue();
                });
    }

    private static List<NodeView> flatten(NodeView root) {
        List<NodeView> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(NodeView node, List<NodeView> out) {
        out.add(node);
        for (NodeView child : node.children()) {
            collect(child, out);
        }
    }
}
