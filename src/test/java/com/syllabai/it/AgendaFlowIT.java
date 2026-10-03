package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assignment.Assignment;
import com.syllabai.assignment.AssignmentRepository;
import com.syllabai.assignment.AssignmentSubmission;
import com.syllabai.assignment.AssignmentSubmissionRepository;
import com.syllabai.assignment.Assignment.Status;
import com.syllabai.assignment.LearnerAssignmentController;
import com.syllabai.assignment.LearnerAssignmentController.SubmissionRequest;
import com.syllabai.assignment.TeacherAssignmentController;
import com.syllabai.assignment.TeacherAssignmentController.CreateRequest;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.classroom.ClassMember;
import com.syllabai.classroom.ClassMemberRepository;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.LearnerAgendaController;
import com.syllabai.learner.ReviewSchedule;
import com.syllabai.learner.ReviewScheduleRepository;
import com.syllabai.learner.dto.AgendaView;
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
 * Integration test: the learner agenda read model (T-C76, Master Spec §22
 * GET /api/v1/learners/me/agenda) against a real Postgres. Pins the whole
 * composition contract end to end:
 *
 * <ul>
 *   <li>honest cold start — a fresh learner gets empty review/assignment
 *       blocks and {@code actions == null} without a rootId, never a
 *       fabricated row;</li>
 *   <li>composition — PENDING spaced reviews surface due-soonest-first with
 *       resolved node titles (never raw UUIDs), visible assignments carry
 *       the learner's own hand-in trail, and the agenda is ordered by due
 *       date (undated last) rather than newest-set;</li>
 *   <li>the V51 visibility boundary inside the composition — a
 *       class-targeted assignment is invisible unless the learner is a
 *       member (the membership rows are the authorization, in an endpoint
 *       that touches three modules at once);</li>
 *   <li>the optional NBA block — a supplied {@code rootId} brings the
 *       deterministic actions view (honest empty actions for a cold
 *       learner); omitting it keeps {@code actions == null}.</li>
 * </ul>
 *
 * <p>Runs in CI where Docker exists; skipped locally otherwise (the same
 * posture as CourseStatsFlowIT, whose honesty pin this lane never weakens:
 * the agenda writes nothing anywhere — submissions in these tests ride the
 * learner's own real write path).</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class AgendaFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SUBJECT_CODE = "4CH1-AGENDA-SUBJECT";
    private static final String TOPIC_A = "4CH1-AGENDA-TOPIC-A";
    private static final String TOPIC_B = "4CH1-AGENDA-TOPIC-B";

    @Autowired
    private AuthService authService;
    @Autowired
    private LearnerAgendaController agendaController;
    @Autowired
    private TeacherAssignmentController teacherController;
    @Autowired
    private LearnerAssignmentController learnerAssignmentController;
    @Autowired
    private ReviewScheduleRepository reviewSchedules;
    @Autowired
    private ClassMemberRepository classMembers;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private AssignmentRepository assignments;
    @Autowired
    private AssignmentSubmissionRepository submissions;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        knowledgeNodes.save(new KnowledgeNode(
                SUBJECT_CODE, NodeType.SUBJECT, "Chemistry (IGCSE) — agenda IT", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                TOPIC_A, NodeType.TOPIC, "Electrolysis (agenda IT)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                TOPIC_B, NodeType.TOPIC, "Rates of reaction (agenda IT)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-agenda-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** The teacher controller carries the class-level role check; invoked
     *  directly (no HTTP) it sees the SecurityContext, not the JWT — install
     *  a teacher authentication first (the AssignmentFlowIT pattern). */
    private UUID newTeacher() {
        UUID id = authService.provisionUser(
                "it-agenda-teacher-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        return id;
    }

    private CreateRequest request(String title, Instant dueAt, UUID classId) {
        return new CreateRequest(title, "igcse-chemistry-19", "IGCSE Chemistry",
                List.of(TOPIC_A), 12, 4, dueAt.toString(), classId);
    }

    private UUID nodeId(String code) {
        return knowledgeNodes.findByCode(code).orElseThrow().id();
    }

    @Test
    @DisplayName("cold start is honest: empty blocks, actions null without a rootId")
    void coldStartHonestEmpty() {
        UUID learner = newLearner();

        AgendaView agenda = agendaController.agenda(learner, null);

        assertThat(agenda.learnerId()).isEqualTo(learner);
        assertThat(agenda.asOf()).isNotNull();
        assertThat(agenda.dueReviews()).isEmpty();
        assertThat(agenda.assignments()).isEmpty();
        assertThat(agenda.actions()).isNull();
    }

    @Test
    @DisplayName("composes reviews + assignments: due-soonest first, titles resolved, hand-ins attached")
    void composesAndOrdersTheAgenda() {
        UUID teacher = newTeacher();
        UUID learner = newLearner();

        // two pending reviews, deliberately seeded out of due order — the
        // agenda must present them due-soonest-first with human titles
        reviewSchedules.save(new ReviewSchedule(learner, nodeId(TOPIC_A),
                Instant.now().plus(5, ChronoUnit.DAYS),
                ReviewSchedule.Reason.DECAY_CROSSED_THRESHOLD, 0.6));
        reviewSchedules.save(new ReviewSchedule(learner, nodeId(TOPIC_B),
                Instant.now().plus(1, ChronoUnit.DAYS),
                ReviewSchedule.Reason.TEACHER_ASSIGNED, 0.7));

        // dated work: due in 5 days; and (created second) due in 1 day
        AssignmentView dueIn5 = teacherController.create(teacher,
                request("Salty chemistry", Instant.now().plus(5, ChronoUnit.DAYS), null));
        AssignmentView dueIn1 = teacherController.create(teacher,
                request("Rates worksheet", Instant.now().plus(1, ChronoUnit.DAYS), null));

        AgendaView agenda = agendaController.agenda(learner, null);

        // reviews: due-soonest first, titles resolved (no raw UUIDs)
        assertThat(agenda.dueReviews()).hasSize(2);
        assertThat(agenda.dueReviews().get(0).nodeName())
                .isEqualTo("Rates of reaction (agenda IT)");
        assertThat(agenda.dueReviews().get(0).reason()).isEqualTo("TEACHER_ASSIGNED");
        assertThat(agenda.dueReviews().get(1).nodeName())
                .isEqualTo("Electrolysis (agenda IT)");
        assertThat(agenda.dueReviews().get(1).reason()).isEqualTo("DECAY_CROSSED_THRESHOLD");

        // assignments: the 1-day worksheet first despite being created second
        assertThat(agenda.assignments()).hasSize(2);
        assertThat(agenda.assignments().get(0).assignment().id()).isEqualTo(dueIn1.id());
        assertThat(agenda.assignments().get(1).assignment().id()).isEqualTo(dueIn5.id());
        assertThat(agenda.assignments().get(0).mySubmission()).isNull();
        assertThat(agenda.assignments().get(1).mySubmission()).isNull();

        // the learner's own hand-in rides the agenda row once they submit
        learnerAssignmentController.submit(learner, dueIn1.id(), new SubmissionRequest(4, 10));
        AgendaView after = agendaController.agenda(learner, null);
        assertThat(after.assignments().get(0).mySubmission()).isNotNull();
        assertThat(after.assignments().get(0).mySubmission().questionsCompleted()).isEqualTo(4);
        assertThat(after.assignments().get(0).mySubmission().score()).isEqualTo(10);

        // another learner's trail never leaks into it
        UUID other = newLearner();
        assertThat(agendaController.agenda(other, null).assignments().stream()
                .filter(v -> v.assignment().id().equals(dueIn1.id()))
                .findFirst().orElseThrow().mySubmission()).isNull();
    }

    @Test
    @DisplayName("V51 visibility holds inside the composition: class work for members only")
    void classVisibilityInsideComposition() {
        UUID teacher = newTeacher();
        UUID member = newLearner();
        UUID outsider = newLearner();
        UUID classId = UUID.randomUUID();

        classMembers.save(new ClassMember(classId, member, teacher));
        teacherController.create(teacher, request("Class-only task",
                Instant.now().plus(3, ChronoUnit.DAYS), classId));

        AgendaView memberAgenda = agendaController.agenda(member, null);
        AgendaView outsiderAgenda = agendaController.agenda(outsider, null);

        assertThat(memberAgenda.assignments()).hasSize(1);
        assertThat(memberAgenda.assignments().get(0).assignment().title())
                .isEqualTo("Class-only task");
        assertThat(outsiderAgenda.assignments()).isEmpty();
    }

    @Test
    @DisplayName("undated work orders last; a rootId brings the NBA block (honest empty for a cold learner)")
    void undatedLastAndOptionalActions() {
        UUID teacher = newTeacher();
        UUID learner = newLearner();

        AssignmentView dated = teacherController.create(teacher,
                request("Dated practice", Instant.now().plus(4, ChronoUnit.DAYS), null));
        // undated work cannot ride the teacher controller's @NotBlank dueAt —
        // the entity accepts null, so seed it through the repository and pin
        // the agenda's undated-last placement
        assignments.save(new Assignment(teacher, "igcse-chemistry-19", "IGCSE Chemistry",
                "Undated reading", List.of(TOPIC_A), 10, 2, null, Status.OPEN));

        AgendaView agenda = agendaController.agenda(learner, null);
        assertThat(agenda.assignments()).hasSize(2);
        assertThat(agenda.assignments().get(0).assignment().id()).isEqualTo(dated.id());
        assertThat(agenda.assignments().get(1).assignment().title()).isEqualTo("Undated reading");
        assertThat(agenda.assignments().get(1).assignment().dueAt()).isNull();

        // with a rootId the deterministic NBA block arrives; a cold learner on
        // a real (childless) subject root gets honest EMPTY actions — advice
        // posture unchanged, never a crash and never fabricated work
        AgendaView withActions = agendaController.agenda(learner, nodeId(SUBJECT_CODE));
        assertThat(withActions.actions()).isNotNull();
        assertThat(withActions.actions().learnerId()).isEqualTo(learner);
        assertThat(withActions.actions().rootId()).isEqualTo(nodeId(SUBJECT_CODE));
        assertThat(withActions.actions().actions()).isEmpty();

        // and the trail: the agenda still wrote nothing (submissions ride the
        // learner's own controller; reviews/assignments predate the reads)
        assertThat(submissions.findByLearnerIdOrderByOccurredAtDesc(learner,
                org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }
}
