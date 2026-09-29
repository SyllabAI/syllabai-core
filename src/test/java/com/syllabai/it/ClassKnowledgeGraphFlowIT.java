package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.classroom.ClassKnowledgeGraphController;
import com.syllabai.classroom.SchoolClassRepository;
import com.syllabai.classroom.TeacherClassController;
import com.syllabai.classroom.TeachingCoverageController;
import com.syllabai.classroom.TeachingCoverageController.MarkRequest;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassGraphNodeView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassKnowledgeGraphView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassView;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the F-072 class-KG heatmap over real Postgres with the
 * V51 classroom foundation and the V52 coverage overlay beneath it. Proves,
 * against the real schema:
 *
 * <ul>
 *   <li>THE INDEPENDENT-STUDENT RULE — a student with NO membership row in
 *       the class never moves a single aggregated number: their skill states
 *       sit in the same tables, on the same nodes, and the payload ignores
 *       them completely;</li>
 *   <li>the §13.3 aggregation — per-node mean over EFFECTIVE (decayed)
 *       mastery, the struggling/developing/proficient distribution by the
 *       shared band vocabulary, evidence counts, active-misconception
 *       prevalence; unmeasured nodes stay honest nulls;</li>
 *   <li>the §13.4 coverage fusion — spec points verbatim from their recorded
 *       rows, topics derived from descendant rows with counts exposed, and
 *       no fabricated states anywhere;</li>
 *   <li>the gates — another teacher 403, unknown class/root 404 — and the
 *       deliberate absence of an archived gate (a teacher may inspect a past
 *       class's heatmap; this endpoint is a pure read model);</li>
 *   <li>THE READ-ONLY PIN — heatmap traffic writes nothing: the learner
 *       model and coverage tables are untouched by any read.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ClassKnowledgeGraphFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String TOPIC_CODE = "4CH1-S1-a";
    private static final String SPEC_1_CODE = "4CH1-1.1";
    private static final String SPEC_2_CODE = "4CH1-1.2";
    private static final String SPEC_3_CODE = "4CH1-1.3";
    private static final String MISCONCEPTION_CODE = "MIS-IT-F072";
    private static final String COURSE = "igcse-chemistry-19";

    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherClassController classController;
    @Autowired
    private TeachingCoverageController coverageController;
    @Autowired
    private ClassKnowledgeGraphController heatmapController;
    @Autowired
    private SchoolClassRepository classRows;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private KnowledgeEdgeRepository knowledgeEdges;
    @Autowired
    private SkillStateRepository skillStates;
    @Autowired
    private MisconceptionStateRepository misconceptionStates;

    private static final Map<String, Object> APPLICABILITY =
            Map.of("papers", List.of("1C", "2C"), "double_award_shared", true);

    @BeforeAll
    static void seedCurriculum(@Autowired KnowledgeNodeRepository knowledgeNodes,
                               @Autowired KnowledgeEdgeRepository knowledgeEdges) {
        KnowledgeNode topic = knowledgeNodes.save(new KnowledgeNode(
                TOPIC_CODE, NodeType.TOPIC, "States of matter", "IT F-072 topic",
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        KnowledgeNode sp1 = specPoint(knowledgeNodes, SPEC_1_CODE,
                "understand the three states of matter");
        KnowledgeNode sp2 = specPoint(knowledgeNodes, SPEC_2_CODE,
                "understand how the kinetic theory explains changes of state");
        KnowledgeNode sp3 = specPoint(knowledgeNodes, SPEC_3_CODE,
                "understand interconversions between the states");
        KnowledgeNode misco = knowledgeNodes.save(new KnowledgeNode(
                MISCONCEPTION_CODE, NodeType.MISCONCEPTION,
                "matter disappears during state changes", "IT F-072 misconception",
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));

        // PART_OF is child → parent: the spec points hang under the topic
        knowledgeEdges.save(new KnowledgeEdge(sp1, topic, RelationType.PART_OF,
                1.0, "it-fixture", KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeEdges.save(new KnowledgeEdge(sp2, topic, RelationType.PART_OF,
                1.0, "it-fixture", KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeEdges.save(new KnowledgeEdge(sp3, topic, RelationType.PART_OF,
                1.0, "it-fixture", KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        // the misconception attaches INTO the topic (MISCONCEPTION_OF → target topic)
        knowledgeEdges.save(new KnowledgeEdge(misco, topic, RelationType.MISCONCEPTION_OF,
                1.0, "it-fixture", KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        // one prerequisite inside the scope: sp2 depends on sp1 (dependent → prerequisite)
        knowledgeEdges.save(new KnowledgeEdge(sp2, sp1, RelationType.REQUIRES_PREREQUISITE,
                1.0, "it-fixture", KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    private static KnowledgeNode specPoint(KnowledgeNodeRepository repo, String code,
                                           String title) {
        KnowledgeNode n = new KnowledgeNode(code, NodeType.SUBTOPIC, title,
                "Official spec point " + code,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it");
        n.setApplicability(new java.util.HashMap<>(APPLICABILITY));
        return repo.save(n);
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private UUID newTeacher() {
        UUID id = authService.provisionUser(
                "it-kg-teacher-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
        // method security (@PreAuthorize) resolves the ROLE_* authority from the
        // SecurityContext on direct controller calls — the ClassroomFlowIT pattern
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        return id;
    }

    /** a student provisioned WITHOUT touching the security context */
    private User newStudent(String name) {
        String email = "it-kg-student-"
                + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        return authService.provisionUser(email, "ItStudent123!", name, Set.of(Role.STUDENT));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID newClass(UUID teacherId) {
        TeacherClassView view = classController.create(teacherId,
                new TeacherClassController.CreateRequest(COURSE, "IGCSE Chemistry", "10A"));
        return view.id();
    }

    private void enroll(UUID teacherId, UUID classId, String email) {
        classController.enroll(teacherId, classId,
                new TeacherClassController.EnrollRequest(email));
    }

    private SkillState skill(UUID learner, UUID node, double mastery, Instant practicedAt,
                             int attempts, int correct) {
        SkillState s = new SkillState(learner, node, mastery, practicedAt);
        for (int i = 0; i < attempts; i++) {
            s.recordAttempt(i < correct, mastery, practicedAt);
        }
        return skillStates.save(s);
    }

    private ClassGraphNodeView node(ClassKnowledgeGraphView view, String code) {
        return view.nodes().stream()
                .filter(n -> code.equals(n.code()))
                .findFirst()
                .orElseThrow();
    }

    // ── the payload ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the heatmap: member-only aggregation, decayed distributions, verbatim + derived coverage, honest empties, read-only provenance")
    void theFullHeatmap() {
        UUID teacher = newTeacher();
        UUID classId = newClass(teacher);
        UUID topicId = knowledgeNodes.findByCode(TOPIC_CODE).orElseThrow().id();
        UUID sp1 = knowledgeNodes.findByCode(SPEC_1_CODE).orElseThrow().id();
        UUID sp2 = knowledgeNodes.findByCode(SPEC_2_CODE).orElseThrow().id();
        UUID sp3 = knowledgeNodes.findByCode(SPEC_3_CODE).orElseThrow().id();
        UUID miscoId = knowledgeNodes.findByCode(MISCONCEPTION_CODE).orElseThrow().id();

        User alice = newStudent("Alice Measure");
        User bob = newStudent("Bob Measure");
        User carol = newStudent("Carol Independent"); // NO membership row — ever
        enroll(teacher, classId, alice.email());
        enroll(teacher, classId, bob.email());

        // member evidence: fresh SECURE + fresh DEVELOPING on sp1; ancient 0.9 on sp2
        skill(alice.id(), sp1, 0.9, Instant.now(), 4, 4);
        skill(bob.id(), sp1, 0.5, Instant.now(), 3, 1);
        skill(bob.id(), sp2, 0.9, Instant.parse("2020-01-01T00:00:00Z"), 5, 5);
        // THE INDEPENDENT-STUDENT PROBE: carol has NO membership row, but her
        // learner model is FULL of evidence on the very same nodes — stronger
        // than any member's. Not one number below may move because of her.
        skill(carol.id(), sp1, 0.99, Instant.now(), 9, 9);
        skill(carol.id(), sp2, 0.99, Instant.now(), 9, 9);

        // active misconception: alice above the BDT threshold, bob below
        misconceptionStates.save(new MisconceptionState(alice.id(), miscoId, 0.7, Instant.now()));
        MisconceptionState inactive = new MisconceptionState(bob.id(), miscoId, 0.3, Instant.now());
        inactive.update(0.3, Instant.now());
        misconceptionStates.save(inactive);

        // the overlay: sp1 taught, sp3 explicitly NOT taught, sp2 unrecorded
        coverageController.mark(teacher, classId, sp1, new MarkRequest("taught", null));
        coverageController.mark(teacher, classId, sp3, new MarkRequest("not-taught", null));

        long skillRowsBefore = skillStates.count();
        long miscoRowsBefore = misconceptionStates.count();

        ClassKnowledgeGraphView view = heatmapController.graph(teacher, classId, topicId);

        // the roster: exactly the two enabled members — carol is not a number here
        assertThat(view.learnersEnrolled()).isEqualTo(2);
        assertThat(view.rootCode()).isEqualTo(TOPIC_CODE);
        assertThat(view.classId()).isEqualTo(classId);

        // sp1: the §13.3 cell — mean AND distribution, member evidence only
        ClassGraphNodeView sp1Node = node(view, SPEC_1_CODE);
        assertThat(sp1Node.coverageState()).isEqualTo("taught");
        assertThat(sp1Node.learnersMeasured()).isEqualTo(2);
        assertThat(sp1Node.meanMastery()).isEqualTo(0.7);
        assertThat(sp1Node.meanBand()).isEqualTo("DEVELOPING");
        assertThat(sp1Node.proficientCount()).isEqualTo(1);
        assertThat(sp1Node.developingCount()).isEqualTo(1);
        assertThat(sp1Node.strugglingCount()).isZero();
        assertThat(sp1Node.attempts()).isEqualTo(7);
        assertThat(sp1Node.correctCount()).isEqualTo(5);

        // sp2: the decay pin — stored 0.9, effective at the 0.1 floor (LOW);
        // unrecorded coverage is the honest absence state
        ClassGraphNodeView sp2Node = node(view, SPEC_2_CODE);
        assertThat(sp2Node.coverageState()).isEqualTo("unrecorded");
        assertThat(sp2Node.learnersMeasured()).isEqualTo(1);
        assertThat(sp2Node.meanMastery()).isEqualTo(0.1);
        assertThat(sp2Node.meanBand()).isEqualTo("LOW");
        assertThat(sp2Node.strugglingCount()).isEqualTo(1);

        // sp3: recorded NOT_TAUGHT with no evidence — not-taught, honestly unmeasured
        ClassGraphNodeView sp3Node = node(view, SPEC_3_CODE);
        assertThat(sp3Node.coverageState()).isEqualTo("not-taught");
        assertThat(sp3Node.learnersMeasured()).isZero();
        assertThat(sp3Node.meanMastery()).isNull();
        assertThat(sp3Node.meanBand()).isEqualTo("UNMEASURED");

        // the topic derives from its descendant rows: 3 spec points, 2 recorded,
        // 1 taught → taught; prevalence counts active members only (alice)
        ClassGraphNodeView topicNode = node(view, TOPIC_CODE);
        assertThat(topicNode.coverageState()).isEqualTo("taught");
        assertThat(topicNode.specPoints()).isEqualTo(3);
        assertThat(topicNode.recordedSpecPoints()).isEqualTo(2);
        assertThat(topicNode.taughtSpecPoints()).isEqualTo(1);
        assertThat(topicNode.learnersMeasured()).isZero();
        assertThat(topicNode.learnersWithActiveMisconception()).isEqualTo(1);

        // the in-scope prerequisite edge survives, codes joined from the registry
        assertThat(view.prerequisiteEdges()).hasSize(1);
        assertThat(view.prerequisiteEdges().get(0).prerequisiteCode()).isEqualTo(SPEC_1_CODE);
        assertThat(view.prerequisiteEdges().get(0).nodeCode()).isEqualTo(SPEC_2_CODE);

        // THE READ-ONLY PIN: the heatmap wrote nothing anywhere
        assertThat(skillStates.count()).isEqualTo(skillRowsBefore);
        assertThat(misconceptionStates.count()).isEqualTo(miscoRowsBefore);
        assertThat(classRows.findById(classId).orElseThrow().status())
                .isEqualTo(com.syllabai.classroom.SchoolClass.Status.ACTIVE);
    }

    // ── gates ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("gates: another teacher 403, unknown class 404, unknown root 404 — and an ARCHIVED class stays readable")
    void gates() {
        UUID teacher = newTeacher();
        UUID other = newTeacher();
        UUID classId = newClass(teacher);
        UUID topicId = knowledgeNodes.findByCode(TOPIC_CODE).orElseThrow().id();

        assertThatThrownBy(() -> heatmapController.graph(other, classId, topicId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> heatmapController.graph(teacher, UUID.randomUUID(), topicId))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> heatmapController.graph(teacher, classId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);

        // archive the class: writes elsewhere close, this READ stays open
        classController.setStatus(teacher, classId,
                new TeacherClassController.StatusRequest("archived"));
        ClassKnowledgeGraphView view = heatmapController.graph(teacher, classId, topicId);
        assertThat(view.className()).isEqualTo("10A");
    }

    // ── honesty on the empty class ──────────────────────────────────────

    @Test
    @DisplayName("an empty class reads honestly: nobody enrolled, every node unmeasured, every spec point unrecorded — nothing fabricated")
    void emptyClassHonesty() {
        UUID teacher = newTeacher();
        UUID classId = newClass(teacher);
        UUID topicId = knowledgeNodes.findByCode(TOPIC_CODE).orElseThrow().id();

        ClassKnowledgeGraphView view = heatmapController.graph(teacher, classId, topicId);

        assertThat(view.learnersEnrolled()).isZero();
        assertThat(view.nodes()).allSatisfy(n -> {
            assertThat(n.learnersMeasured()).isZero();
            assertThat(n.meanMastery()).isNull();
            assertThat(n.meanBand()).isEqualTo("UNMEASURED");
        });
        assertThat(view.nodes())
                .filteredOn(n -> "SUBTOPIC".equals(n.type()))
                .allSatisfy(n -> {
                    assertThat(n.coverageState()).isEqualTo("unrecorded");
                    assertThat(n.recordedSpecPoints()).isZero();
                });
    }
}
