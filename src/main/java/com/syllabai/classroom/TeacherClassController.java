package com.syllabai.classroom;

import com.syllabai.classroom.dto.ClassroomViews.ClassMemberView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherAnnouncementView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassDetailView;
import com.syllabai.classroom.dto.ClassroomViews.TeacherClassView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher class management (TFA-01, V51; TEACHER_ARCHITECTURE §8 + §9):
 * create classes on a hub course, enroll/remove students, publish
 * announcements with read-state, and archive. Route security:
 * /api/v1/teacher/** requires TEACHER or ADMIN (SecurityConfig) plus this
 * class-level check (deep-audit M5 defense in depth) AND per-object
 * ownership checks below — a teacher must only reach classes they own
 * (§17: backend authorization is mandatory, the UI hiding data is not).
 *
 * <p>HONESTY RULES: class and announcement traffic writes no learner-model
 * state (pinned by ClassroomFlowIT); enrollment requires an ENABLED STUDENT
 * account (enrolling a teacher or a disabled account would quietly create a
 * fake classroom); re-enrollment is the idempotent no-op it honestly is.</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/classes")
// deep-audit 09-28 M5: method-level role check mirroring the route rule
// in SecurityConfig (defense in depth — the route matchers stay authoritative)
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TeacherClassController {

    private static final int LIST_LIMIT = 100;
    private static final int ANNOUNCEMENT_LIMIT = 100;

    private final SchoolClassRepository classes;
    private final ClassMemberRepository members;
    private final AnnouncementRepository announcements;
    private final AnnouncementReadRepository announcementReads;
    private final UserRepository users;

    public TeacherClassController(SchoolClassRepository classes,
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

    // ── class lifecycle ─────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherClassView create(@CurrentUserId UUID teacherId,
                                   @Valid @RequestBody CreateRequest request) {
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("class name must not be blank");
        }
        boolean clash = classes.findByTeacherIdAndCourseSlugAndStatusOrderByCreatedAtDesc(
                        teacherId, request.courseSlug(), SchoolClass.Status.ACTIVE,
                        PageRequest.of(0, LIST_LIMIT))
                .stream()
                .anyMatch(c -> c.name().trim().equalsIgnoreCase(name));
        if (clash) {
            throw new ConflictException("you already have a live class with this name on this course");
        }
        SchoolClass saved = classes.save(new SchoolClass(
                teacherId, request.courseSlug(), request.courseLabel(), name));
        return TeacherClassView.of(saved, 0);
    }

    @GetMapping
    public List<TeacherClassView> list(@CurrentUserId UUID teacherId) {
        List<SchoolClass> mine = classes.findByTeacherIdOrderByCreatedAtDesc(
                teacherId, PageRequest.of(0, LIST_LIMIT));
        Map<UUID, Long> counts = memberCounts(mine);
        return mine.stream()
                .map(c -> TeacherClassView.of(c, counts.getOrDefault(c.id(), 0L)))
                .toList();
    }

    @GetMapping("/{id}")
    public TeacherClassDetailView detail(@CurrentUserId UUID teacherId, @PathVariable UUID id) {
        SchoolClass c = ownedClass(teacherId, id);
        List<ClassMember> roster = members.findByClassIdOrderByEnrolledAtAsc(id);
        Map<UUID, User> studentById = loadUsers(
                roster.stream().map(ClassMember::studentId).toList());
        List<ClassMemberView> rows = roster.stream()
                .map(m -> {
                    User u = studentById.get(m.studentId());
                    // a member row whose account was hard-removed (never
                    // through any path this module offers) still shows, named
                    // honestly, rather than vanishing from the roster
                    return new ClassMemberView(
                            m.studentId(),
                            u != null ? u.displayName() : "(removed account)",
                            u != null ? u.email() : "(unavailable)",
                            m.enrolledBy(),
                            m.enrolledAt());
                })
                .toList();
        return new TeacherClassDetailView(c.id(), c.courseSlug(), c.courseLabel(),
                c.name(), c.status().wire(), c.createdAt(), rows);
    }

    @PostMapping("/{id}/status")
    public TeacherClassView setStatus(@CurrentUserId UUID teacherId, @PathVariable UUID id,
                                      @Valid @RequestBody StatusRequest request) {
        SchoolClass c = ownedClass(teacherId, id);
        SchoolClass.Status parsed = SchoolClass.Status.parse(request.status());
        if (parsed == null) {
            throw new BadRequestException("status must be 'active' or 'archived'");
        }
        c.status(parsed);
        long count = members.findByClassIdOrderByEnrolledAtAsc(id).size();
        return TeacherClassView.of(classes.save(c), count);
    }

    // ── membership ──────────────────────────────────────────────────────

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherClassDetailView enroll(@CurrentUserId UUID teacherId, @PathVariable UUID id,
                                         @Valid @RequestBody EnrollRequest request) {
        SchoolClass c = ownedClass(teacherId, id);
        if (c.status() != SchoolClass.Status.ACTIVE) {
            throw new ConflictException("this class is archived — reopen it before enrolling");
        }
        String email = request.email().trim().toLowerCase();
        if (email.isEmpty()) {
            throw new BadRequestException("email must not be blank");
        }
        User student = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException(
                        "no account with that email — the student registers first, then you enroll"));
        if (!student.enabled()) {
            throw new ConflictException("that account is disabled");
        }
        if (!student.roles().contains(Role.STUDENT)) {
            throw new ConflictException("only student accounts can be enrolled in a class");
        }
        if (!members.existsByClassIdAndStudentId(id, student.id())) {
            members.save(new ClassMember(id, student.id(), teacherId));
        }
        return detail(teacherId, id);
    }

    @DeleteMapping("/{id}/members/{studentId}")
    @Transactional
    public TeacherClassDetailView removeMember(@CurrentUserId UUID teacherId,
                                               @PathVariable UUID id,
                                               @PathVariable UUID studentId) {
        ownedClass(teacherId, id);
        members.findByClassIdOrderByEnrolledAtAsc(id).stream()
                .filter(m -> m.studentId().equals(studentId))
                .forEach(members::delete);
        return detail(teacherId, id);
    }

    // ── announcements ───────────────────────────────────────────────────

    @PostMapping("/{id}/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherAnnouncementView publish(@CurrentUserId UUID teacherId, @PathVariable UUID id,
                                           @Valid @RequestBody PublishRequest request) {
        SchoolClass c = ownedClass(teacherId, id);
        if (c.status() != SchoolClass.Status.ACTIVE) {
            throw new ConflictException("this class is archived — reopen it before publishing");
        }
        Announcement.Category category = Announcement.Category.parse(request.category());
        if (category == null) {
            throw new BadRequestException(
                    "category must be general, homework, notice, exam-reminder or resource");
        }
        String title = request.title().trim();
        String body = request.body().trim();
        if (title.isEmpty() || body.isEmpty()) {
            throw new BadRequestException("title and body must not be blank");
        }
        Announcement saved = announcements.save(
                new Announcement(teacherId, id, title, body, category));
        long memberCount = members.findByClassIdOrderByEnrolledAtAsc(id).size();
        return new TeacherAnnouncementView(saved.id(), saved.title(), saved.body(),
                saved.category().wire(), 0, memberCount, saved.createdAt());
    }

    @GetMapping("/{id}/announcements")
    public List<TeacherAnnouncementView> announcements(@CurrentUserId UUID teacherId,
                                                       @PathVariable UUID id) {
        ownedClass(teacherId, id);
        List<Announcement> rows = announcements.findByClassIdOrderByCreatedAtDesc(
                id, PageRequest.of(0, ANNOUNCEMENT_LIMIT));
        List<ClassMember> roster = members.findByClassIdOrderByEnrolledAtAsc(id);
        Map<UUID, Long> readCounts = readCounts(rows);
        return rows.stream()
                .map(a -> new TeacherAnnouncementView(a.id(), a.title(), a.body(),
                        a.category().wire(),
                        readCounts.getOrDefault(a.id(), 0L),
                        roster.size(),
                        a.createdAt()))
                .toList();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** the §17 gate: the class must exist AND be owned by this teacher */
    private SchoolClass ownedClass(UUID teacherId, UUID classId) {
        SchoolClass c = classes.findById(classId)
                .orElseThrow(() -> new NotFoundException("class not found"));
        if (!c.teacherId().equals(teacherId)) {
            throw new ForbiddenException("this class belongs to another teacher");
        }
        return c;
    }

    private Map<UUID, Long> memberCounts(List<SchoolClass> mine) {
        if (mine.isEmpty()) return Map.of();
        Map<UUID, Long> out = new HashMap<>();
        for (var row : classes.countMembersByClassId(
                mine.stream().map(SchoolClass::id).toList())) {
            out.put(row.getClassId(), row.getMemberCount());
        }
        return out;
    }

    private Map<UUID, Long> readCounts(List<Announcement> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] pair : announcementReads.countByAnnouncementIds(
                rows.stream().map(Announcement::id).toList())) {
            out.put((UUID) pair[0], (Long) pair[1]);
        }
        return out;
    }

    private Map<UUID, User> loadUsers(List<UUID> ids) {
        Map<UUID, User> out = new HashMap<>();
        for (UUID id : new ArrayList<>(ids)) {
            users.findById(id).ifPresent(u -> out.put(id, u));
        }
        return out;
    }

    // ── request bodies ──────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank @Size(max = 64) String courseSlug,
            @NotBlank @Size(max = 120) String courseLabel,
            @NotBlank @Size(max = 120) String name) {
    }

    public record EnrollRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record PublishRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank String body,
            @Size(max = 20) String category) {
    }

    public record StatusRequest(@NotBlank String status) {
    }
}
