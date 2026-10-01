package com.syllabai.classroom;

import com.syllabai.classroom.dto.ClassroomViews.LearnerAnnouncementView;
import com.syllabai.classroom.dto.ClassroomViews.LearnerClassView;
import com.syllabai.classroom.dto.ClassroomViews.LearnerClassroomView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The classroom student capability layer (TFA-02, V51; TEACHER_ARCHITECTURE
 * §4.2): class-enrolled students read their classes and announcements from
 * HERE and only from here — membership rows are the single source of
 * visibility.
 *
 * <p>THE INDEPENDENT-STUDENT RULE (operator directive, TEACHER_ARCHITECTURE
 * §4.2): a student with no membership rows gets empty reads — an empty
 * list, an honest zero — and the hub renders no classroom surface at all.
 * The classroom relationship grants capabilities; it never changes the
 * learner model, the curriculum, or the independent experience. A student
 * may simultaneously be independent and class-enrolled: these reads never
 * gate the independent features, they only ADD the classroom ones.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/classroom")
// learner route: any authenticated user may call, but every read below is
// derived from membership rows only — no membership, no classroom data
@PreAuthorize("isAuthenticated()")
public class LearnerClassroomController {

    private static final int ANNOUNCEMENT_LIMIT = 100;

    private final SchoolClassRepository classes;
    private final ClassMemberRepository members;
    private final AnnouncementRepository announcements;
    private final AnnouncementReadRepository announcementReads;
    private final UserRepository users;

    public LearnerClassroomController(SchoolClassRepository classes,
                                      ClassMemberRepository members,
                                      AnnouncementRepository announcements,
                                      AnnouncementReadRepository announcementReads,
                                      UserRepository users) {
        this.classes = classes;
        this.members = members;
        this.announcements = announcements;
        this.announcementReads = announcementReads;
        this.users = users;
    }

    /** my classes: the classroom overlay root. Empty for the independent
     *  student — that empty IS the honest state, not an error. */
    @GetMapping
    public LearnerClassroomView overview(@CurrentUserId UUID learnerId) {
        ClassroomWorkspace ws = workspace(learnerId);
        List<LearnerClassView> rows = new ArrayList<>();
        for (ClassMember m : ws.memberships) {
            SchoolClass c = ws.classById.get(m.classId());
            List<Announcement> feed = ws.announcementsByClass
                    .getOrDefault(c.id(), List.of());
            long unread = feed.stream().filter(a -> !ws.readIds.contains(a.id())).count();
            rows.add(new LearnerClassView(c.id(), c.courseSlug(), c.courseLabel(),
                    c.name(), ws.teacherName.apply(c.teacherId()), unread, m.enrolledAt()));
        }
        long totalUnread = rows.stream().mapToLong(LearnerClassView::unreadAnnouncements).sum();
        return new LearnerClassroomView(rows, totalUnread);
    }

    /** announcements across ALL my live classes, newest first, with my
     *  read-state beside each. */
    @GetMapping("/announcements")
    public List<LearnerAnnouncementView> announcements(@CurrentUserId UUID learnerId) {
        ClassroomWorkspace ws = workspace(learnerId);
        List<LearnerAnnouncementView> out = new ArrayList<>();
        for (ClassMember m : ws.memberships) {
            SchoolClass c = ws.classById.get(m.classId());
            for (Announcement a : ws.announcementsByClass.getOrDefault(c.id(), List.of())) {
                out.add(LearnerAnnouncementView.of(a, c.name(), c.courseSlug(),
                        ws.teacherName.apply(c.teacherId()), ws.readIds.contains(a.id())));
            }
        }
        return out;
    }

    /** mark one announcement read — idempotent, membership-gated. */
    @PostMapping("/announcements/{id}/read")
    public ReadResult markRead(@CurrentUserId UUID learnerId, @PathVariable UUID id) {
        Announcement a = announcements.findById(id)
                .orElseThrow(() -> new NotFoundException("announcement not found"));
        // membership gate: a student can only mark reads inside their own
        // classroom — never another teacher's class (TEACHER_ARCHITECTURE §17)
        if (!members.existsByClassIdAndStudentId(a.classId(), learnerId)) {
            throw new ForbiddenException("this announcement is not in your classroom");
        }
        if (!announcementReads.existsByAnnouncementIdAndStudentId(id, learnerId)) {
            announcementReads.save(new AnnouncementRead(id, learnerId));
        }
        return new ReadResult(id, true);
    }

    /** the learner's classroom workspace, assembled once per request: live
     *  memberships, their classes, the bounded announcement feed, and MY
     *  read-state set — batched so the honest overlay costs four queries,
     *  not one per row. */
    private ClassroomWorkspace workspace(UUID learnerId) {
        List<ClassMember> memberships = members.findByStudentIdOrderByEnrolledAtAsc(learnerId);
        if (memberships.isEmpty()) {
            return ClassroomWorkspace.EMPTY;
        }
        Map<UUID, SchoolClass> classById = new HashMap<>();
        memberships.stream().map(ClassMember::classId).distinct()
                .forEach(id -> classes.findById(id).ifPresent(c -> classById.put(id, c)));
        // archived classes drop out of the student overlay: the class ended,
        // and pretending it is still live would be the dishonest direction
        List<ClassMember> live = memberships.stream()
                .filter(m -> {
                    SchoolClass c = classById.get(m.classId());
                    return c != null && c.status() == SchoolClass.Status.ACTIVE;
                })
                .toList();
        if (live.isEmpty()) {
            return ClassroomWorkspace.EMPTY;
        }
        List<UUID> liveIds = live.stream().map(ClassMember::classId).toList();
        List<Announcement> feed = announcements.findByClassIdInOrderByCreatedAtDesc(
                liveIds, PageRequest.of(0, ANNOUNCEMENT_LIMIT));
        Set<UUID> readIds = feed.isEmpty()
                ? Set.of()
                : new HashSet<>(announcements.findReadIds(learnerId,
                        feed.stream().map(Announcement::id).toList()));
        Map<UUID, List<Announcement>> byClass = new HashMap<>();
        for (Announcement a : feed) {
            byClass.computeIfAbsent(a.classId(), k -> new ArrayList<>()).add(a);
        }
        Map<UUID, String> teacherNames = new HashMap<>();
        classById.values().stream().map(SchoolClass::teacherId).distinct()
                .forEach(id -> users.findById(id)
                        .ifPresentOrElse(u -> teacherNames.put(id, u.displayName()),
                                () -> teacherNames.put(id, "(unavailable)")));
        Function<UUID, String> teacherName = id ->
                teacherNames.getOrDefault(id, "(unavailable)");
        return new ClassroomWorkspace(live, classById, byClass, readIds, teacherName);
    }

    /** wire shape for the read receipt (package-private for tests) */
    record ReadResult(UUID id, boolean read) {
    }

    /** request-scoped view of everything the overlay reads */
    private record ClassroomWorkspace(
            List<ClassMember> memberships,
            Map<UUID, SchoolClass> classById,
            Map<UUID, List<Announcement>> announcementsByClass,
            Set<UUID> readIds,
            Function<UUID, String> teacherName) {

        static final ClassroomWorkspace EMPTY = new ClassroomWorkspace(
                List.of(), Map.of(), Map.of(), Set.of(), id -> "(unavailable)");
    }
}
