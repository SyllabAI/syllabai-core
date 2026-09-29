package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.classroom.dto.ClassroomViews.TeacherClassDetailView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassView;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the teacher class management surface (V51, TFA-01): the
 * §17 ownership gate (another teacher's class is unreachable — detail,
 * enroll, publish all refuse), the fail-closed enrollment gates (unknown
 * email 404, non-student 409, duplicate = idempotent no-op), and the
 * live-name uniqueness rule. HTTP-layer RBAC is pinned separately
 * (TeacherRouteSecurityIT pattern); these pin the object-level rules.
 */
class TeacherClassControllerTest {

    private final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final AnnouncementReadRepository announcementReads =
            mock(AnnouncementReadRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TeacherClassController controller = new TeacherClassController(
            classes, members, announcements, announcementReads, users);

    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final String COURSE = "igcse-chemistry-19";

    private SchoolClass liveClass() {
        return new SchoolClass(TEACHER, COURSE, "IGCSE Chemistry", "10A") {
            // stable id for assertions
            @Override
            public UUID id() {
                return CLASS_ID;
            }
        };
    }

    private User studentUser(String email) {
        User u = mock(User.class);
        when(u.id()).thenReturn(UUID.randomUUID());
        when(u.email()).thenReturn(email);
        when(u.displayName()).thenReturn("It Learner");
        when(u.enabled()).thenReturn(true);
        when(u.roles()).thenReturn(Set.of(Role.STUDENT));
        return u;
    }

    @Test
    @DisplayName("ownership gate: another teacher's class is 403 on every surface")
    void ownershipGate() {
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(liveClass()));

        assertThatThrownBy(() -> controller.detail(UUID.randomUUID(), CLASS_ID))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.enroll(UUID.randomUUID(), CLASS_ID,
                new TeacherClassController.EnrollRequest("x@syllabai.test")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.publish(UUID.randomUUID(), CLASS_ID,
                new TeacherClassController.PublishRequest("t", "b", null)))
                .isInstanceOf(ForbiddenException.class);
        verify(members, never()).save(any());
        verify(announcements, never()).save(any());
    }

    @Test
    @DisplayName("duplicate live class name on the same course refuses (409)")
    void duplicateLiveNameRefuses() {
        when(classes.findByTeacherIdAndCourseSlugAndStatusOrderByCreatedAtDesc(
                eq(TEACHER), eq(COURSE), eq(SchoolClass.Status.ACTIVE), any()))
                .thenReturn(List.of(liveClass()));

        assertThatThrownBy(() -> controller.create(TEACHER,
                new TeacherClassController.CreateRequest(COURSE, "IGCSE Chemistry", "10A")))
                .isInstanceOf(ConflictException.class);
        verify(classes, never()).save(any());
    }

    @Test
    @DisplayName("enrollment: unknown email 404, non-student 409, duplicate idempotent")
    void enrollmentGates() {
        SchoolClass c = liveClass();
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(c));
        when(members.findByClassIdOrderByEnrolledAtAsc(CLASS_ID)).thenReturn(List.of());

        when(users.findByEmailIgnoreCase("ghost@syllabai.test")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.enroll(TEACHER, CLASS_ID,
                new TeacherClassController.EnrollRequest("ghost@syllabai.test")))
                .isInstanceOf(NotFoundException.class);

        User teacherAccount = studentUser("t@syllabai.test");
        when(teacherAccount.roles()).thenReturn(Set.of(Role.TEACHER));
        when(users.findByEmailIgnoreCase("t@syllabai.test"))
                .thenReturn(Optional.of(teacherAccount));
        assertThatThrownBy(() -> controller.enroll(TEACHER, CLASS_ID,
                new TeacherClassController.EnrollRequest("t@syllabai.test")))
                .isInstanceOf(ConflictException.class);

        User student = studentUser("s@syllabai.test");
        when(users.findByEmailIgnoreCase("s@syllabai.test")).thenReturn(Optional.of(student));
        when(members.existsByClassIdAndStudentId(CLASS_ID, student.id())).thenReturn(true);
        TeacherClassDetailView reEnroll = controller.enroll(TEACHER, CLASS_ID,
                new TeacherClassController.EnrollRequest("S@syllabai.test"));
        assertThat(reEnroll.members()).isEmpty(); // no duplicate row written
        verify(members, never()).save(any());
    }

    @Test
    @DisplayName("archived class refuses enrollment and publishing; status wire round-trips")
    void archiveLifecycle() {
        SchoolClass c = liveClass();
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(c));
        when(members.findByClassIdOrderByEnrolledAtAsc(CLASS_ID)).thenReturn(List.of());
        when(classes.save(c)).thenReturn(c);

        TeacherClassView archived = controller.setStatus(TEACHER, CLASS_ID,
                new TeacherClassController.StatusRequest("archived"));
        assertThat(archived.status()).isEqualTo("archived");

        assertThatThrownBy(() -> controller.enroll(TEACHER, CLASS_ID,
                new TeacherClassController.EnrollRequest("s@syllabai.test")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> controller.publish(TEACHER, CLASS_ID,
                new TeacherClassController.PublishRequest("t", "b", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("announcement read counts derive from receipts over the real roster")
    void announcementReadCounts() {
        SchoolClass c = liveClass();
        when(classes.findById(CLASS_ID)).thenReturn(Optional.of(c));
        List<ClassMember> roster = List.of(
                new ClassMember(CLASS_ID, UUID.randomUUID(), TEACHER),
                new ClassMember(CLASS_ID, UUID.randomUUID(), TEACHER));
        when(members.findByClassIdOrderByEnrolledAtAsc(CLASS_ID)).thenReturn(roster);

        Announcement a = new Announcement(TEACHER, CLASS_ID, "Title", "Body",
                Announcement.Category.GENERAL) {
            // stable id: @PrePersist never fires in a unit test, so the
            // constructor leaves id null and List.of(null) would NPE
            @Override
            public UUID id() {
                return ANNOUNCEMENT_ID;
            }
        };
        when(announcements.findByClassIdOrderByCreatedAtDesc(eq(CLASS_ID), any()))
                .thenReturn(List.of(a));
        when(announcementReads.countByAnnouncementIds(List.of(ANNOUNCEMENT_ID)))
                .thenReturn(List.<Object[]>of(new Object[]{ANNOUNCEMENT_ID, 1L}));

        var feed = controller.announcements(TEACHER, CLASS_ID);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).readCount()).isEqualTo(1);
        assertThat(feed.get(0).memberCount()).isEqualTo(2);
    }
}
