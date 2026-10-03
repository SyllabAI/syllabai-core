package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assignment.TeacherAssignmentController;
import com.syllabai.assignment.TeacherAssignmentController.CreateRequest;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.classroom.ClassMember;
import com.syllabai.classroom.ClassMemberRepository;
import com.syllabai.classroom.SchoolClass;
import com.syllabai.classroom.SchoolClassRepository;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.assignment.LearnerAssignmentController;
import com.syllabai.assignment.LearnerAssignmentController.SubmissionRequest;
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
 *   <li>honest cold start — a fresh learner's learner-SCOPED blocks are
 *       empty (due reviews) and {@code actions == null} without a rootId;
 *       the assignments block is pinned on VISIBILITY CORRECTNESS instead of
 *       emptiness, because the container's database is shared across test
 *       methods and a V49 whole-cohort row (null class target) is
 *       legitimately visible to every learner — a fresh learner with no
 *       memberships must never see a class-targeted row and never see a
 *       hand-in that is not theirs;</li>
 *   <li>composition — PENDING spaced reviews surface due-soonest-first with
 *       resolved node titles (never raw UUIDs), the assignments this test
 *       created are ordered due-soonest-first, and the learner's hand-in
 *       rides the row once submitted;</li>
 *   <li>the V51 visibility boundary inside the composition — a
 *       class-targeted assignment is invisible unless the learner is a
 *       member (the membership rows are the authorization, in an endpoint
 *       that touches three modules at once; the class must be a REAL classes
 *       row — fk_cmember_class);</li>
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
    private SchoolClassRepository classes;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;

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
    @DisplayName("cold start is honest: learner-scoped blocks empty, V51 visibility correct, actions null")
    void coldStartHonestEmpty() {
        UUID learner = newLearner();

        AgendaView agenda = agendaController.agenda(learner, null);

        assertThat(agenda.learnerId()).isEqualTo(learner);
        assertThat(agenda.asOf()).isNotNull();
        // learner-scoped: a fresh learner has no spaced-review rows at all
        assertThat(agenda.dueReviews()).isEmpty();
        assertThat(agenda.actions()).isNull();

        // the assignments block on a SHARED container cannot be pinned on
        // emptiness — other tests' whole-cohort rows (null class target =
        // the whole enabled cohort, V49) are legitimately visible. The pin
        // is VISIBILITY CORRECTNESS: with no memberships, this learner sees
        // only cohort rows and never a hand-in that is not theirs.
        assertThat(agenda.assignments())
                .allSatisfy(v -> assertThat(v.assignment().classId()).isNull());
        assertThat(agenda.assignments())
                .allSatisfy(v -> assertThat(v.mySubmission()).isNull());
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

        // dated work (due_at is NOT NULL — V49): due in 5 days; and
        // (created second) due in 1 day — must surface FIRST
        AssignmentView dueIn5 = teacherController.create(teacher,
                request("Salty chemistry " + UUID.randomUUID().toString().substring(0, 6),
                        Instant.now().plus(5, ChronoUnit.DAYS), null));
        AssignmentView dueIn1 = teacherController.create(teacher,
                request("Rates worksheet " + UUID.randomUUID().toString().substring(0, 6),
                        Instant.now().plus(1, ChronoUnit.DAYS), null));

        AgendaView agenda = agendaController.agenda(learner, null);

        // reviews: due-soonest first, titles resolved (no raw UUIDs)
        assertThat(agenda.dueReviews()).hasSize(2);
        assertThat(agenda.dueReviews().get(0).nodeName())
                .isEqualTo("Rates of reaction (agenda IT)");
        assertThat(agenda.dueReviews().get(0).reason()).isEqualTo("TEACHER_ASSIGNED");
        assertThat(agenda.dueReviews().get(1).nodeName())
                .isEqualTo("Electrolysis (agenda IT)");
        assertThat(agenda.dueReviews().get(1).reason()).isEqualTo("DECAY_CROSSED_THRESHOLD");

        // assignments: THIS test's two rows, ordered due-soonest-first
        // (filtered to the ids this test created — the shared container
        // legitimately carries other tests' cohort rows too)
        List<UUID> mine = List.of(dueIn1.id(), dueIn5.id());
        var rows = agenda.assignments().stream()
                .filter(v -> mine.contains(v.assignment().id()))
                .toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).assignment().id()).isEqualTo(dueIn1.id());
        assertThat(rows.get(1).assignment().id()).isEqualTo(dueIn5.id());
        assertThat(rows.get(0).mySubmission()).isNull();
        assertThat(rows.get(1).mySubmission()).isNull();

        // the learner's own hand-in rides the agenda row once they submit
        learnerAssignmentController.submit(learner, dueIn1.id(), new SubmissionRequest(4, 10));
        AgendaView after = agendaController.agenda(learner, null);
        var rowAfter = after.assignments().stream()
                .filter(v -> v.assignment().id().equals(dueIn1.id()))
                .findFirst().orElseThrow();
        assertThat(rowAfter.mySubmission()).isNotNull();
        assertThat(rowAfter.mySubmission().questionsCompleted()).isEqualTo(4);
        assertThat(rowAfter.mySubmission().score()).isEqualTo(10);

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

        // the class must be a REAL classes row — fk_cmember_class enforces it
        UUID classId = classes.save(new SchoolClass(teacher, "igcse-chemistry-19",
                "IGCSE Chemistry", "Agenda IT " + UUID.randomUUID().toString().substring(0, 6)))
                .id();
        classMembers.save(new ClassMember(classId, member, teacher));
        teacherController.create(teacher, request("Class-only task",
                Instant.now().plus(3, ChronoUnit.DAYS), classId));

        AgendaView memberAgenda = agendaController.agenda(member, null);
        AgendaView outsiderAgenda = agendaController.agenda(outsider, null);

        // pin THIS test's class-targeted row (the shared container carries
        // other tests' cohort rows, which are legitimately visible to both)
        assertThat(memberAgenda.assignments().stream()
                .filter(v -> classId.equals(v.assignment().classId()))).hasSize(1);
        assertThat(memberAgenda.assignments().stream()
                .filter(v -> classId.equals(v.assignment().classId()))
                .findFirst().orElseThrow().assignment().title()).contains("Class-only task");
        assertThat(outsiderAgenda.assignments().stream()
                .filter(v -> classId.equals(v.assignment().classId()))).isEmpty();
    }

    @Test
    @DisplayName("a rootId brings the NBA block (honest empty for a cold learner); without it actions stay null")
    void optionalActionsBlock() {
        UUID learner = newLearner();

        AgendaView withoutRoot = agendaController.agenda(learner, null);
        assertThat(withoutRoot.actions()).isNull();

        // with a rootId the deterministic NBA block arrives; a cold learner on
        // a real (childless) subject root gets honest EMPTY actions — advice
        // posture unchanged, never a crash and never fabricated work
        AgendaView withRoot = agendaController.agenda(learner, nodeId(SUBJECT_CODE));
        assertThat(withRoot.actions()).isNotNull();
        assertThat(withRoot.actions().learnerId()).isEqualTo(learner);
        assertThat(withRoot.actions().rootId()).isEqualTo(nodeId(SUBJECT_CODE));
        assertThat(withRoot.actions().policy()).startsWith("nba-rules/");
        assertThat(withRoot.actions().actions()).isEmpty();

        // and the trail: the agenda wrote nothing anywhere — no submissions
        // exist for this learner (the reviews/assignments rows predate it)
        assertThat(withRoot.assignments())
                .allSatisfy(v -> assertThat(v.mySubmission()).isNull());
    }
}
