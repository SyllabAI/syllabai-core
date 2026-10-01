package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.assignment.AssignmentRepository;
import com.syllabai.assignment.AssignmentSubmissionRepository;
import com.syllabai.assignment.LearnerAssignmentController;
import com.syllabai.assignment.LearnerAssignmentController.SubmissionRequest;
import com.syllabai.assignment.TeacherAssignmentController;
import com.syllabai.assignment.TeacherAssignmentController.CreateRequest;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.assignment.dto.AssignmentViews.LearnerAssignmentView;
import com.syllabai.classroom.AnnouncementRepository;
import com.syllabai.classroom.ClassMemberRepository;
import com.syllabai.classroom.LearnerClassroomController;
import com.syllabai.classroom.SchoolClassRepository;
import com.syllabai.classroom.TeacherClassController;
import com.syllabai.classroom.dto.ClassroomViews.LearnerAnnouncementView;
import com.syllabai.classroom.dto.ClassroomViews.LearnerClassroomView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherAnnouncementView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassDetailView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassView;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.Role;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
 * Integration test: the classroom foundation (V51, TFA-01 + TFA-02) against
 * a real Postgres. Pins the whole teacher→class→student overlay end to end:
 *
 * <ul>
 *   <li>a teacher creates a class on a hub course, enrolls ONE of two
 *       students by email, and publishes an announcement;</li>
 *   <li>THE INDEPENDENT-STUDENT RULE — the enrolled student sees the class
 *       and the announcement; the independent student sees an EMPTY
 *       classroom (no classes, zero unread) and NO class-targeted
 *       assignment, while BOTH students keep seeing the V49 NULL-target
 *       assignment (the independent experience never changes);</li>
 *   <li>class-targeted assignments — fail-closed creation gates (unknown
 *       class 404, another teacher's class 403, wrong course 400, archived
 *       class 409), member-only visibility, member-only hand-ins, and the
 *       roster showing exactly the class members;</li>
 *   <li>authorization — a second teacher cannot read, enroll into, or
 *       publish into the first teacher's class (§17: backend enforcement,
 *       not UI chrome); a non-member student cannot mark an announcement
 *       read;</li>
 *   <li>read state — marking read is idempotent and the teacher's read
 *       count follows the real roster;</li>
 *   <li>THE HONESTY PIN — classroom traffic writes no learner-model state:
 *       SkillStateRepository stays empty after create/enroll/publish/
 *       read/hand-in traffic (mastery comes from marked attempts only).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ClassroomFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String TARGET_CODE = "4CH1-S1-a";
    private static final String SUBJECT_CODE = "4CH1";
    private static final String COURSE_SLUG = "igcse-chemistry-19";

    @Autowired
    private AuthService authService;
    @Autowired
    private TeacherClassController classController;
    @Autowired
    private LearnerClassroomController learnerClassroom;
    @Autowired
    private TeacherAssignmentController assignmentController;
    @Autowired
    private LearnerAssignmentController learnerAssignments;
    @Autowired
    private SchoolClassRepository classRows;
    @Autowired
    private ClassMemberRepository memberRows;
    @Autowired
    private AnnouncementRepository announcementRows;
    @Autowired
    private AssignmentRepository assignmentRows;
    @Autowired
    private AssignmentSubmissionRepository submissionRows;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        knowledgeNodes.save(new KnowledgeNode(
                TARGET_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                SUBJECT_CODE, NodeType.SUBJECT, "Chemistry (IGCSE)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** learner registry: id → email (the enroll flow is email-keyed) */
    private final Map<UUID, String> memberEmails = new HashMap<>();

    private UUID newLearner() {
        String email = "it-cf-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        UUID id = authService.register(new RegisterRequest(
                email,
                "ItLearner123!", "It Learner")).user().id();
        memberEmails.put(id, email);
        return id;
    }

    private UUID newTeacher() {
        UUID id = authService.provisionUser(
                "it-cf-teacher-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItTeacher123!", "It Teacher", Set.of(Role.TEACHER)).id();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        return id;
    }

    private CreateRequest cohortRequest(String title) {
        return new CreateRequest(title, COURSE_SLUG, "IGCSE Chemistry",
                List.of(TARGET_CODE), 12, 4,
                Instant.now().plus(7, ChronoUnit.DAYS).toString(), null);
    }

    private CreateRequest classRequest(String title, UUID classId) {
        return new CreateRequest(title, COURSE_SLUG, "IGCSE Chemistry",
                List.of(TARGET_CODE), 12, 4,
                Instant.now().plus(7, ChronoUnit.DAYS).toString(), classId);
    }

    @Test
    @DisplayName("class overlay: enrolled student sees classroom, independent student does not")
    void classroomOverlaySplit() {
        UUID teacher = newTeacher();
        UUID enrolled = newLearner();
        UUID independent = newLearner();

        TeacherClassView created = classController.create(teacher, new TeacherClassController.CreateRequest(
                COURSE_SLUG, "IGCSE Chemistry", "10A"));
        assertThat(created.status()).isEqualTo("active");
        assertThat(created.memberCount()).isZero();

        // enroll ONE of the two learners by email
        TeacherClassDetailView afterEnroll = classController.enroll(teacher, created.id(),
                new TeacherClassController.EnrollRequest(memberEmails.get(enrolled)));
        assertThat(afterEnroll.members()).hasSize(1);
        assertThat(afterEnroll.members().get(0).studentId()).isEqualTo(enrolled);

        // publish an announcement
        TeacherAnnouncementView announcement = classController.publish(teacher, created.id(),
                new TeacherClassController.PublishRequest(
                        "Test on Thursday", "Bring calculators.", "exam-reminder"));
        assertThat(announcement.category()).isEqualTo("exam-reminder");
        assertThat(announcement.readCount()).isZero();
        assertThat(announcement.memberCount()).isEqualTo(1);

        // ENROLLED student: one live class, one unread announcement
        LearnerClassroomView enrolledView = learnerClassroom.overview(enrolled);
        assertThat(enrolledView.classes()).hasSize(1);
        assertThat(enrolledView.classes().get(0).name()).isEqualTo("10A");
        assertThat(enrolledView.classes().get(0).unreadAnnouncements()).isEqualTo(1);
        assertThat(enrolledView.unreadAnnouncements()).isEqualTo(1);
        List<LearnerAnnouncementView> enrolledFeed = learnerClassroom.announcements(enrolled);
        assertThat(enrolledFeed).hasSize(1);
        assertThat(enrolledFeed.get(0).read()).isFalse();

        // INDEPENDENT student: honest empties — no classes, no announcements
        LearnerClassroomView independentView = learnerClassroom.overview(independent);
        assertThat(independentView.classes()).isEmpty();
        assertThat(independentView.unreadAnnouncements()).isZero();
        assertThat(learnerClassroom.announcements(independent)).isEmpty();

        // read-state: idempotent, and the teacher's count follows the roster
        learnerClassroom.markRead(enrolled, announcement.id());
        learnerClassroom.markRead(enrolled, announcement.id());
        assertThat(learnerClassroom.announcements(enrolled).get(0).read()).isTrue();
        List<TeacherAnnouncementView> teacherFeed =
                classController.announcements(teacher, created.id());
        assertThat(teacherFeed.get(0).readCount()).isEqualTo(1);

        // the independent student cannot mark another class's announcement read
        assertThatThrownBy(() -> learnerClassroom.markRead(independent, announcement.id()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("class-targeted assignments: member-only visibility, hand-ins and roster")
    void classTargetedAssignments() {
        UUID teacher = newTeacher();
        UUID member = newLearner();
        UUID independent = newLearner();

        TeacherClassView created = classController.create(teacher,
                new TeacherClassController.CreateRequest(COURSE_SLUG, "IGCSE Chemistry", "10B"));
        classController.enroll(teacher, created.id(),
                new TeacherClassController.EnrollRequest(memberEmails.get(member)));

        // NULL target keeps the V49 default: BOTH students see it
        AssignmentView cohortAssignment =
                assignmentController.create(teacher, cohortRequest("Cohort practice"));
        // class target: fail-closed gates first
        assertThatThrownBy(() -> assignmentController.create(teacher,
                classRequest("Ghost class", UUID.randomUUID())))
                .isInstanceOf(com.syllabai.shared.NotFoundException.class);
        AssignmentView classAssignment =
                assignmentController.create(teacher, classRequest("10B only", created.id()));

        // visibility split
        List<LearnerAssignmentView> memberView = learnerAssignments.list(member);
        assertThat(memberView.stream().map(v -> v.assignment().id()))
                .contains(cohortAssignment.id(), classAssignment.id());
        List<LearnerAssignmentView> independentView = learnerAssignments.list(independent);
        assertThat(independentView.stream().map(v -> v.assignment().id()))
                .contains(cohortAssignment.id())
                .doesNotContain(classAssignment.id());

        // hand-in gate: member accepted, independent refused (403)
        learnerAssignments.submit(member, classAssignment.id(), new SubmissionRequest(2, 5));
        assertThatThrownBy(() -> learnerAssignments.submit(
                independent, classAssignment.id(), new SubmissionRequest(2, 5)))
                .isInstanceOf(ForbiddenException.class);

        // roster shows exactly the class members with real state
        var roster = assignmentController.roster(classAssignment.id());
        assertThat(roster.rows()).hasSize(1);
        assertThat(roster.rows().get(0).learnerId()).isEqualTo(member);
        assertThat(roster.rows().get(0).state()).isEqualTo("complete");

        // the cohort assignment's roster still covers every enabled student
        var cohortRoster = assignmentController.roster(cohortAssignment.id());
        assertThat(cohortRoster.rows())
                .extracting(r -> r.learnerId())
                .contains(member, independent);

        // summary denominators follow the target
        var summaries = assignmentController.list();
        assertThat(summaries.stream()
                .filter(s -> s.assignment().id().equals(classAssignment.id()))
                .findFirst().orElseThrow().submitted()).isEqualTo(1);
    }

    @Test
    @DisplayName("authorization: another teacher cannot reach the class; creation gates hold")
    void teacherAuthorizationAndGates() {
        UUID teacherA = newTeacher();
        UUID teacherB = newTeacher();
        UUID student = newLearner();

        TeacherClassView created = classController.create(teacherA,
                new TeacherClassController.CreateRequest(COURSE_SLUG, "IGCSE Chemistry", "10C"));

        // duplicate live name on the same course is a 409
        assertThatThrownBy(() -> classController.create(teacherA,
                new TeacherClassController.CreateRequest(COURSE_SLUG, "IGCSE Chemistry", "10c")))
                .isInstanceOf(ConflictException.class);

        // teacherB: list is own-only; detail/enroll/publish are 403
        assertThat(classController.list(teacherB)).isEmpty();
        assertThatThrownBy(() -> classController.detail(teacherB, created.id()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> classController.enroll(teacherB, created.id(),
                new TeacherClassController.EnrollRequest(memberEmails.get(student))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> classController.publish(teacherB, created.id(),
                new TeacherClassController.PublishRequest("Hi", "Body", null)))
                .isInstanceOf(ForbiddenException.class);

        // an assignment from teacherB targeting teacherA's class is 403 and persists nothing
        long before = assignmentRows.count();
        assertThatThrownBy(() -> assignmentController.create(teacherB,
                classRequest("Crossover", created.id())))
                .isInstanceOf(ForbiddenException.class);
        assertThat(assignmentRows.count()).isEqualTo(before);

        // class/course mismatch is a 400
        assertThatThrownBy(() -> assignmentController.create(teacherA,
                new CreateRequest("Wrong course", "some-other-course", "Other",
                        List.of(TARGET_CODE), 12, 4,
                        Instant.now().plus(1, ChronoUnit.DAYS).toString(), created.id())))
                .isInstanceOf(com.syllabai.shared.BadRequestException.class);

        // archive closes the classroom: no enroll, no publish afterwards
        classController.setStatus(teacherA, created.id(),
                new TeacherClassController.StatusRequest("archived"));
        assertThatThrownBy(() -> classController.enroll(teacherA, created.id(),
                new TeacherClassController.EnrollRequest(memberEmails.get(student))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> classController.publish(teacherA, created.id(),
                new TeacherClassController.PublishRequest("Hi", "Body", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("THE HONESTY PIN — classroom traffic writes no learner-model state")
    void classroomWritesNoMastery() {
        UUID teacher = newTeacher();
        UUID student = newLearner();

        TeacherClassView created = classController.create(teacher,
                new TeacherClassController.CreateRequest(COURSE_SLUG, "IGCSE Chemistry", "10D"));
        classController.enroll(teacher, created.id(),
                new TeacherClassController.EnrollRequest(memberEmails.get(student)));
        TeacherAnnouncementView announcement = classController.publish(teacher, created.id(),
                new TeacherClassController.PublishRequest("Notice", "Body", "general"));
        learnerClassroom.markRead(student, announcement.id());
        AssignmentView classAssignment =
                assignmentController.create(teacher, classRequest("10D work", created.id()));
        learnerAssignments.submit(student, classAssignment.id(), new SubmissionRequest(1, null));

        assertThat(skillStates.count()).isZero();
        // scoped to THIS class: the IT methods share one container/DB, so a
        // global member count would see other tests' enrollments too
        assertThat(memberRows.findByClassIdOrderByEnrolledAtAsc(created.id())).hasSize(1);
        assertThat(announcementRows.findByClassIdOrderByCreatedAtDesc(
                created.id(), org.springframework.data.domain.Pageable.unpaged())).hasSize(1);
    }
}
