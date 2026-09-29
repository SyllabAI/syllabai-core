package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.classroom.SchoolClass;
import com.syllabai.classroom.SchoolClassRepository;
import com.syllabai.classroom.TeachingCoverage;
import com.syllabai.classroom.TeachingCoverageController;
import com.syllabai.classroom.TeachingCoverageController.MarkRequest;
import com.syllabai.classroom.TeachingCoverageEventRepository;
import com.syllabai.classroom.TeachingCoverageRepository;
import com.syllabai.classroom.TeacherClassController;
import com.syllabai.classroom.dto.TeachingCoverageViews.CoverageEventView;
import com.syllabai.classroom.dto.TeachingCoverageViews.CoverageRowView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassView;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
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
 * Integration test: the teaching-coverage overlay over real Postgres with
 * the V51 classroom foundation beneath it (V52, TFA-03). Proves, against the
 * real schema and the real constraint set:
 *
 * <ul>
 *   <li>the marking lifecycle — first assertion writes one state row + one
 *       audit event (NULL previous_status); an identical re-mark is the
 *       honest no-op (no event, no marked_at bump); a status flip appends
 *       exactly one event carrying previous → new and moves the row; the
 *       per-point history serves the trail newest-first verbatim;</li>
 *   <li>the fail-closed gates — another teacher's class is 403 on every
 *       route, an archived class is 409, an unknown node is 404, and the
 *       V39 spec-point gate refuses any node without applicability (a TOPIC
 *       structure node is "not a specification point"), including for a
 *       node that exists but is not a spec point;</li>
 *   <li>honest reads — the class list contains ONLY asserted rows (a class
 *       with no markings lists empty; the unmarked spec point of another
 *       class is simply absent, never a fabricated NOT_TAUGHT row);</li>
 *   <li>THE HONESTY PIN — coverage traffic writes no learner-model state:
 *       SkillStateRepository stays empty after every mark/read here (NOT_TAUGHT
 *       is not a mastery state; nothing in BKT/SkillState/misconception/
 *       review may read or write this module);</li>
 *   <li>NO CURRICULUM MUTATION — the seeded knowledge_nodes rows are
 *       byte-identical after all coverage traffic (code, title, description,
 *       validation status, applicability object); the overlay references the
 *       curriculum, it never alters it.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class TeachingCoverageFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SPEC_POINT_CODE = "4CH1-1.1";
    private static final String SPEC_POINT_2_CODE = "4CH1-1.2";
    private static final String TOPIC_CODE = "4CH1-S1-a";
    private static final String COURSE = "igcse-chemistry-19";

    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherClassController classController;
    @Autowired
    private TeachingCoverageController coverageController;
    @Autowired
    private SchoolClassRepository classRows;
    @Autowired
    private TeachingCoverageRepository coverageRows;
    @Autowired
    private TeachingCoverageEventRepository eventRows;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    private static final Map<String, Object> APPLICABILITY =
            Map.of("papers", List.of("1C", "2C"), "double_award_shared", true);

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        KnowledgeNode sp1 = new KnowledgeNode(
                SPEC_POINT_CODE, NodeType.SUBTOPIC,
                "understand the three states of matter",
                "Official spec point 1.1", KnowledgeNode.ValidationStatus.VALIDATED,
                "it-fixture", "it");
        sp1.setApplicability(new java.util.HashMap<>(APPLICABILITY));
        knowledgeNodes.save(sp1);
        KnowledgeNode sp2 = new KnowledgeNode(
                SPEC_POINT_2_CODE, NodeType.SUBTOPIC,
                "understand how the kinetic theory explains changes of state",
                "Official spec point 1.2", KnowledgeNode.ValidationStatus.VALIDATED,
                "it-fixture", "it");
        sp2.setApplicability(new java.util.HashMap<>(APPLICABILITY));
        knowledgeNodes.save(sp2);
        knowledgeNodes.save(new KnowledgeNode(
                TOPIC_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    private UUID newTeacher() {
        UUID id = authService.provisionUser(
                "it-tc-teacher-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
        // method security (@PreAuthorize) resolves the ROLE_* authority from the
        // SecurityContext on direct controller calls — the ClassroomFlowIT pattern
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        return id;
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

    private UUID specPointId(String code) {
        return knowledgeNodes.findByCode(code).orElseThrow().id();
    }

    private String curriculumFingerprint() {
        List<String> rows = new java.util.ArrayList<>();
        knowledgeNodes.findByCode(SPEC_POINT_CODE).stream()
                .map(n -> n.code() + "|" + n.title() + "|" + n.description() + "|"
                        + n.validationStatus() + "|" + n.applicability())
                .forEach(rows::add);
        knowledgeNodes.findByCode(TOPIC_CODE).stream()
                .map(n -> n.code() + "|" + n.title() + "|" + n.description() + "|"
                        + n.validationStatus() + "|" + n.applicability())
                .forEach(rows::add);
        return String.join(";;", rows);
    }

    @Test
    @DisplayName("marking lifecycle: first assertion, honest no-op on identical re-mark, audited flip, verbatim history")
    void markingLifecycle() {
        UUID teacher = newTeacher();
        UUID classId = newClass(teacher);
        UUID sp1 = specPointId(SPEC_POINT_CODE);

        CoverageRowView first = coverageController.mark(teacher, classId, sp1,
                new MarkRequest("taught", "finished before half term"));
        assertThat(first.status()).isEqualTo("taught");
        assertThat(first.code()).isEqualTo(SPEC_POINT_CODE);
        assertThat(first.title()).contains("three states of matter");
        assertThat(first.note()).isEqualTo("finished before half term");
        assertThat(first.markedBy()).isEqualTo(teacher);
        assertThat(first.firstMarkedAt()).isEqualTo(first.markedAt());

        // the list serves the row joined with the node identity — and nothing else
        List<CoverageRowView> listed = coverageController.list(teacher, classId);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).specPointNodeId()).isEqualTo(sp1);
        assertThat(listed.get(0).code()).isEqualTo(SPEC_POINT_CODE);

        // identical re-mark: the honest no-op — no event, no timestamp bump
        java.time.Instant markedAtBefore = coverageRows
                .findByClassIdAndSpecPointNodeId(classId, sp1).orElseThrow().markedAt();
        CoverageRowView again = coverageController.mark(teacher, classId, sp1,
                new MarkRequest("taught", "finished before half term"));
        assertThat(again.markedAt()).isEqualTo(markedAtBefore);
        assertThat(eventRows.findByClassIdAndSpecPointNodeIdOrderByCreatedAtDesc(classId, sp1))
                .hasSize(1);

        // whitespace-padded same note trims to the same assertion — still a no-op
        // (a blank note on a NOTED row would be a note CLEARANCE, an information
        // change that is honestly audited — the null-note no-op is pinned in the
        // unit suite)
        coverageController.mark(teacher, classId, sp1,
                new MarkRequest("taught", "  finished before half term  "));
        assertThat(eventRows.findByClassIdAndSpecPointNodeIdOrderByCreatedAtDesc(classId, sp1))
                .hasSize(1);

        // the flip: exactly one new event carrying previous → new
        CoverageRowView flipped = coverageController.mark(teacher, classId, sp1,
                new MarkRequest("not-taught", "syllabus re-plan — moved to next term"));
        assertThat(flipped.status()).isEqualTo("not-taught");
        List<CoverageEventView> trail =
                coverageController.history(teacher, classId, sp1);
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).status()).isEqualTo("not-taught");
        assertThat(trail.get(0).previousStatus()).isEqualTo("taught");
        assertThat(trail.get(0).note()).isEqualTo("syllabus re-plan — moved to next term");
        assertThat(trail.get(1).previousStatus()).isNull();
        assertThat(trail.get(1).note()).isEqualTo("finished before half term");

        // the state row carries the first-assertion provenance even after the flip
        TeachingCoverage row = coverageRows
                .findByClassIdAndSpecPointNodeId(classId, sp1).orElseThrow();
        assertThat(row.createdAt()).isEqualTo(first.firstMarkedAt());
    }

    @Test
    @DisplayName("honest reads: a class with no markings lists empty — no fabricated NOT_TAUGHT rows")
    void honestEmptyReads() {
        UUID teacher = newTeacher();
        UUID classId = newClass(teacher);

        assertThat(coverageController.list(teacher, classId)).isEmpty();
        assertThat(coverageController.history(teacher, classId, specPointId(SPEC_POINT_CODE)))
                .isEmpty();
        assertThat(coverageRows.findByClassIdOrderBySpecPointNodeIdAsc(classId)).isEmpty();
    }

    @Test
    @DisplayName("gates: another teacher 403 on every route; archived 409; unknown node 404; non-spec-point 400")
    void gates() {
        UUID teacher = newTeacher();
        UUID other = newTeacher();
        UUID classId = newClass(teacher);
        UUID sp1 = specPointId(SPEC_POINT_CODE);
        UUID topic = specPointId(TOPIC_CODE);

        // another teacher: every route refuses (§17 — backend, not UI chrome)
        assertThatThrownBy(() -> coverageController.mark(other, classId, sp1, new MarkRequest("taught", null)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> coverageController.list(other, classId))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> coverageController.history(other, classId, sp1))
                .isInstanceOf(ForbiddenException.class);

        // unknown node 404 — but ONLY after the ownership gate
        UUID unknownNode = UUID.randomUUID();
        assertThatThrownBy(() -> coverageController.mark(teacher, classId, unknownNode,
                new MarkRequest("taught", null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("specification point");

        // THE V39 SPEC-POINT GATE: the TOPIC node exists but is not a spec point
        assertThatThrownBy(() -> coverageController.mark(teacher, classId, topic,
                new MarkRequest("taught", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not a specification point");
        assertThat(coverageRows.findByClassIdAndSpecPointNodeId(classId, topic)).isEmpty();

        // bad status vocabulary
        assertThatThrownBy(() -> coverageController.mark(teacher, classId, sp1,
                new MarkRequest("covered", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("taught");

        // archive the class: reads stay open, writes close (409)
        classController.setStatus(teacher, classId,
                new TeacherClassController.StatusRequest("archived"));
        assertThatThrownBy(() -> coverageController.mark(teacher, classId, sp1,
                new MarkRequest("taught", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("archived");
        assertThat(coverageController.list(teacher, classId)).isEmpty();

        // a second class still marks fine on the same spec point (scope is per class)
        UUID class2 = newClass(teacher);
        coverageController.mark(teacher, class2, sp1, new MarkRequest("not-taught", null));
        assertThat(coverageRows.findByClassIdAndSpecPointNodeId(class2, sp1)).isPresent();
    }

    @Test
    @DisplayName("THE HONESTY PIN — coverage traffic writes no learner-model state, and the curriculum is untouched")
    void honestyAndImmutabilityPins() {
        UUID teacher = newTeacher();
        UUID classId = newClass(teacher);
        String curriculumBefore = curriculumFingerprint();

        coverageController.mark(teacher, classId, specPointId(SPEC_POINT_CODE),
                new MarkRequest("taught", "pinned"));
        coverageController.mark(teacher, classId, specPointId(SPEC_POINT_2_CODE),
                new MarkRequest("not-taught", null));
        coverageController.list(teacher, classId);
        coverageController.history(teacher, classId, specPointId(SPEC_POINT_CODE));
        coverageController.mark(teacher, classId, specPointId(SPEC_POINT_2_CODE),
                new MarkRequest("taught", "caught up"));

        // NOT_TAUGHT is not a mastery state: no SkillState row exists anywhere
        assertThat(skillStates.count()).isZero();

        // no curriculum mutation: the seeded nodes are byte-identical
        assertThat(curriculumFingerprint()).isEqualTo(curriculumBefore);
    }
}
