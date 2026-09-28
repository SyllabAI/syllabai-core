package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.assignment.AssignmentRepository;
import com.syllabai.assignment.AssignmentSubmissionRepository;
import com.syllabai.assignment.LearnerAssignmentController;
import com.syllabai.assignment.LearnerAssignmentController.SubmissionRequest;
import com.syllabai.assignment.TeacherAssignmentController;
import com.syllabai.assignment.TeacherAssignmentController.CreateRequest;
import com.syllabai.assignment.TeacherAssignmentController.StatusRequest;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentRosterView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentSubmissionView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.assignment.dto.AssignmentViews.LearnerAssignmentView;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Integration test: the assignments contract (V49, ADR-029 tranche 4.10)
 * against a real Postgres. Pins the whole two-party workflow end to end:
 *
 * <ul>
 *   <li>a teacher creates an assignment from validated curriculum targets;
 *       a learner in another browser sees it and hands it in; the teacher's
 *       roster shows the REAL completion state (complete/late/missing);</li>
 *   <li>append-only hand-ins — a re-hand-in is a new row and the latest one
 *       is the current state (the trail keeps the work history);</li>
 *   <li>lifecycle — a closed assignment refuses hand-ins (409), reopen
 *       accepts them again;</li>
 *   <li>FAIL-CLOSED TARGETS — an unknown code, the subject root and a
 *       semantic-layer node are all 404 and persist NOTHING;</li>
 *   <li>bounds — a hand-in can never claim more questions than the
 *       assignment has, nor a score above its marks total;</li>
 *   <li>THE HONESTY PIN — assignments never produce mastery:
 *       SkillStateRepository stays empty after creation + hand-in traffic;
 *       mastery comes from marked attempts only.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class AssignmentFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String TARGET_CODE = "4CH1-S1-a";
    private static final String SUBJECT_CODE = "4CH1";
    private static final String CONCEPT_CODE = "4CH1-CON-IT-FIXTURE";

    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherAssignmentController teacherController;
    @Autowired
    private LearnerAssignmentController learnerController;
    @Autowired
    private AssignmentRepository assignmentRows;
    @Autowired
    private AssignmentSubmissionRepository submissionRows;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        // mirrors the production 4CH1 graph's level naming (same ruling as
        // V47 ratings / V48 votes — the anchor level ingests as TOPIC)
        knowledgeNodes.save(new KnowledgeNode(
                TARGET_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                SUBJECT_CODE, NodeType.SUBJECT, "Chemistry (IGCSE)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                CONCEPT_CODE, NodeType.CONCEPT, "IT concept fixture", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-as-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** The teacher controller carries the class-level @PreAuthorize role
     *  check; these tests invoke it DIRECTLY (no HTTP), so the role check
     *  sees the SecurityContext, not the JWT — install a teacher
     *  authentication before touching the teacher surface (cleared after
     *  each test). HTTP-layer RBAC is pinned separately by
     *  TeacherRouteSecurityIT. */
    private UUID newTeacher() {
        UUID id = authService.provisionUser(
                "it-as-teacher-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        return id;
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private CreateRequest request(String title, List<String> refs, Instant dueAt) {
        return new CreateRequest(title, "igcse-chemistry-19", "IGCSE Chemistry",
                refs, 12, 4, dueAt.toString());
    }

    private long submissionRowsOf(UUID learner) {
        return submissionRows.findByLearnerIdOrderByOccurredAtDesc(learner,
                org.springframework.data.domain.Pageable.unpaged()).size();
    }

    @Test
    @DisplayName("teacher creates → learner sees and hands in → roster shows real completion")
    void twoPartyHappyPath() throws InterruptedException {
        UUID teacher = newTeacher();
        UUID learnerA = newLearner();
        UUID learnerB = newLearner();

        AssignmentView created = teacherController.create(teacher, request(
                "States of matter practice", List.of(TARGET_CODE),
                Instant.now().plus(7, ChronoUnit.DAYS)));
        assertThat(created.title()).isEqualTo("States of matter practice");
        assertThat(created.status()).isEqualTo("open");
        assertThat(created.specRefs()).containsExactly(TARGET_CODE);
        assertThat(created.marksTotal()).isEqualTo(12);
        assertThat(created.questionCount()).isEqualTo(4);

        // the learner sees it — no hand-in yet
        List<LearnerAssignmentView> before = learnerController.list(learnerA);
        LearnerAssignmentView mine = before.stream()
                .filter(v -> v.assignment().id().equals(created.id())).findFirst().orElseThrow();
        assertThat(mine.mySubmission()).isNull();

        // hand in
        AssignmentSubmissionView handedIn = learnerController.submit(learnerA,
                created.id(), new SubmissionRequest(4, 10));
        assertThat(handedIn.questionsCompleted()).isEqualTo(4);
        assertThat(handedIn.score()).isEqualTo(10);

        // learner A's list now carries the hand-in; learner B's does not
        assertThat(learnerController.list(learnerA).stream()
                .filter(v -> v.assignment().id().equals(created.id())).findFirst().orElseThrow()
                .mySubmission().score()).isEqualTo(10);
        assertThat(learnerController.list(learnerB).stream()
                .filter(v -> v.assignment().id().equals(created.id())).findFirst().orElseThrow()
                .mySubmission()).isNull();

        // the teacher's roster shows the real state: A complete, B missing
        AssignmentRosterView roster = teacherController.roster(created.id());
        assertThat(roster.assignment().id()).isEqualTo(created.id());
        var rowA = roster.rows().stream().filter(r -> r.learnerId().equals(learnerA))
                .findFirst().orElseThrow();
        assertThat(rowA.state()).isEqualTo("complete");
        assertThat(rowA.score()).isEqualTo(10);
        assertThat(rowA.questionsCompleted()).isEqualTo(4);
        var rowB = roster.rows().stream().filter(r -> r.learnerId().equals(learnerB))
                .findFirst().orElseThrow();
        assertThat(rowB.state()).isEqualTo("missing");
        assertThat(rowB.submittedAt()).isNull();
    }

    @Test
    @DisplayName("a hand-in after the due date is 'late' on the roster")
    void lateHandIn() {
        UUID teacher = newTeacher();
        UUID learner = newLearner();

        AssignmentView created = teacherController.create(teacher, request(
                "Overdue catch-up", List.of(TARGET_CODE),
                Instant.now().minus(2, ChronoUnit.DAYS)));
        learnerController.submit(learner, created.id(), new SubmissionRequest(2, null));

        var row = teacherController.roster(created.id()).rows().stream()
                .filter(r -> r.learnerId().equals(learner)).findFirst().orElseThrow();
        assertThat(row.state()).isEqualTo("late");
        assertThat(row.score()).isNull(); // unmarked hand-in is honest — no fabricated 0
    }

    @Test
    @DisplayName("fail-closed targets: unknown code, subject root and semantic node are 404 and persist nothing")
    void failClosedTargets() {
        UUID teacher = newTeacher();
        long before = assignmentRows.count();

        assertThatThrownBy(() -> teacherController.create(teacher,
                request("x", List.of("4CH1-S99-z"), Instant.now())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> teacherController.create(teacher,
                request("x", List.of(SUBJECT_CODE), Instant.now())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> teacherController.create(teacher,
                request("x", List.of(TARGET_CODE, CONCEPT_CODE), Instant.now())))
                .isInstanceOf(NotFoundException.class);

        assertThat(assignmentRows.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("lifecycle: closed refuses hand-ins (409), reopen accepts again")
    void closedLifecycle() throws InterruptedException {
        UUID teacher = newTeacher();
        UUID learner = newLearner();

        AssignmentView created = teacherController.create(teacher, request(
                "Window test", List.of(TARGET_CODE), Instant.now().plus(1, ChronoUnit.DAYS)));
        UUID id = created.id();

        learnerController.submit(learner, id, new SubmissionRequest(1, null)); // open: works
        Thread.sleep(60);
        teacherController.setStatus(id, new StatusRequest("closed"));
        assertThatThrownBy(() -> learnerController.submit(learner, id,
                new SubmissionRequest(2, null)))
                .isInstanceOf(ConflictException.class);
        Thread.sleep(60);
        AssignmentView reopened = teacherController.setStatus(id, new StatusRequest("open"));
        assertThat(reopened.status()).isEqualTo("open");
        learnerController.submit(learner, id, new SubmissionRequest(3, 5));

        // three hand-ins total, latest (3q/5 marks) is the current state
        assertThat(submissionRowsOf(learner)).isEqualTo(3);
        assertThat(learnerController.list(learner).stream()
                .filter(v -> v.assignment().id().equals(id)).findFirst().orElseThrow()
                .mySubmission().questionsCompleted()).isEqualTo(3);
    }

    @Test
    @DisplayName("re-hand-in appends: the latest row is the current state, the trail keeps history")
    void reSubmissionAppends() throws InterruptedException {
        UUID teacher = newTeacher();
        UUID learner = newLearner();
        AssignmentView created = teacherController.create(teacher, request(
                "Improve your score", List.of(TARGET_CODE), Instant.now().plus(1, ChronoUnit.DAYS)));

        learnerController.submit(learner, created.id(), new SubmissionRequest(2, 4));
        Thread.sleep(60);
        learnerController.submit(learner, created.id(), new SubmissionRequest(4, 9));

        assertThat(submissionRowsOf(learner)).isEqualTo(2);
        var current = learnerController.list(learner).stream()
                .filter(v -> v.assignment().id().equals(created.id())).findFirst().orElseThrow()
                .mySubmission();
        assertThat(current.score()).isEqualTo(9);
        assertThat(current.questionsCompleted()).isEqualTo(4);
        // the roster reads the latest row only
        var row = teacherController.roster(created.id()).rows().stream()
                .filter(r -> r.learnerId().equals(learner)).findFirst().orElseThrow();
        assertThat(row.score()).isEqualTo(9);
    }

    @Test
    @DisplayName("bounds: more questions than the assignment has, or a score above the marks total, is a 400")
    void boundsValidation() {
        UUID teacher = newTeacher();
        UUID learner = newLearner();
        AssignmentView created = teacherController.create(teacher, request(
                "Bounded work", List.of(TARGET_CODE), Instant.now().plus(1, ChronoUnit.DAYS)));

        assertThatThrownBy(() -> learnerController.submit(learner, created.id(),
                new SubmissionRequest(5, null))) // 5 > questionCount 4
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> learnerController.submit(learner, created.id(),
                new SubmissionRequest(2, 13))) // 13 > marksTotal 12
                .isInstanceOf(BadRequestException.class);
        assertThat(submissionRowsOf(learner)).isZero();
    }

    @Test
    @DisplayName("THE HONESTY PIN: assignments are completion evidence only — no SkillState row is ever created")
    void assignmentsNeverProduceMastery() {
        UUID teacher = newTeacher();
        UUID learner = newLearner();
        AssignmentView created = teacherController.create(teacher, request(
                "Mastery-free by construction", List.of(TARGET_CODE),
                Instant.now().plus(1, ChronoUnit.DAYS)));
        learnerController.submit(learner, created.id(), new SubmissionRequest(4, 12));
        teacherController.setStatus(created.id(), new StatusRequest("closed"));

        // creation + hand-in + lifecycle traffic, and yet: zero mastery state.
        // Mastery comes from marked attempts ONLY (the learner-model honesty rule).
        assertThat(skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learner)).isEmpty();
        LearnerStateView state = stateController.state(learner);
        assertThat(state.skillStates()).isEmpty();
        // ...and the self-report evidence slices are untouched by assignment traffic
        assertThat(state.flashcardRatings()).isEmpty();
        assertThat(state.noteVotes()).isEmpty();
    }
}
