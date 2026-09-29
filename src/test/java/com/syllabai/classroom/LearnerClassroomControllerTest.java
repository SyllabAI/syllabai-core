package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syllabai.classroom.dto.ClassroomViews.LearnerClassroomView;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the learner classroom overlay (V51, TFA-02) — above all
 * THE INDEPENDENT-STUDENT RULE: no membership rows → empty reads, and no
 * other repository is even touched (the empty case must not leak or cost).
 * The membership gate on mark-read pins §17 for the student side.
 */
class LearnerClassroomControllerTest {

    private final SchoolClassRepository classes = mock(SchoolClassRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final AnnouncementRepository announcements = mock(AnnouncementRepository.class);
    private final AnnouncementReadRepository announcementReads =
            mock(AnnouncementReadRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LearnerClassroomController controller = new LearnerClassroomController(
            classes, members, announcements, announcementReads, users);

    private static final UUID LEARNER = UUID.randomUUID();

    @Test
    @DisplayName("THE INDEPENDENT-STUDENT RULE — no membership: empty reads, zero queries beyond membership")
    void independentStudentReadsEmpty() {
        when(members.findByStudentIdOrderByEnrolledAtAsc(LEARNER)).thenReturn(List.of());

        LearnerClassroomView view = controller.overview(LEARNER);
        assertThat(view.classes()).isEmpty();
        assertThat(view.unreadAnnouncements()).isZero();
        assertThat(controller.announcements(LEARNER)).isEmpty();

        // the honest empty costs nothing and touches nothing else
        verifyNoInteractions(classes, announcements, announcementReads, users);
    }

    @Test
    @DisplayName("archived membership drops out of the overlay — still an honest empty")
    void archivedClassDropsOut() {
        UUID classId = UUID.randomUUID();
        SchoolClass archived = new SchoolClass(UUID.randomUUID(), "c", "C", "Old") {
            @Override
            public SchoolClass.Status status() {
                return SchoolClass.Status.ARCHIVED;
            }
        };
        when(members.findByStudentIdOrderByEnrolledAtAsc(LEARNER))
                .thenReturn(List.of(new ClassMember(classId, LEARNER, UUID.randomUUID())));
        when(classes.findById(classId)).thenReturn(Optional.of(archived));

        assertThat(controller.overview(LEARNER).classes()).isEmpty();
        assertThat(controller.announcements(LEARNER)).isEmpty();
    }

    @Test
    @DisplayName("mark-read: unknown announcement 404; non-member 403; member idempotent")
    void markReadGates() {
        UUID announcementId = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        when(announcements.findById(announcementId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.markRead(LEARNER, announcementId))
                .isInstanceOf(NotFoundException.class);

        Announcement a = new Announcement(UUID.randomUUID(), classId, "T", "B",
                Announcement.Category.HOMEWORK);
        when(announcements.findById(announcementId)).thenReturn(Optional.of(a));

        when(members.existsByClassIdAndStudentId(classId, LEARNER)).thenReturn(false);
        assertThatThrownBy(() -> controller.markRead(LEARNER, announcementId))
                .isInstanceOf(ForbiddenException.class);
        verify(announcementReads, never()).save(any());

        when(members.existsByClassIdAndStudentId(classId, LEARNER)).thenReturn(true);
        when(announcementReads.existsByAnnouncementIdAndStudentId(announcementId, LEARNER))
                .thenReturn(true); // already read
        var result = controller.markRead(LEARNER, announcementId);
        assertThat(result.read()).isTrue();
        verify(announcementReads, never()).save(any()); // idempotent no-op
    }
}
