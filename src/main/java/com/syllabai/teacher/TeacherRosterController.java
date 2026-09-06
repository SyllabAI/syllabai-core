package com.syllabai.teacher;

import com.syllabai.identity.Role;
import com.syllabai.identity.UserRepository;
import com.syllabai.teacher.dto.TeacherViews;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher class roster (T-029 minimal surface, Master Spec §6.10/§22). Route
 * security: /api/v1/teacher/** requires TEACHER or ADMIN (SecurityConfig).
 *
 * <p>The pilot has no class/section entity, so the honest "class list" is the
 * enabled STUDENT cohort ordered by display name — identity projection only
 * (no learning data: the marking queue and /state carry that). Class
 * management is deliberately out of Cycle-1 scope.</p>
 */
@RestController
@RequestMapping("/api/v1/teacher")
public class TeacherRosterController {

    private final UserRepository users;

    public TeacherRosterController(UserRepository users) {
        this.users = users;
    }

    /** the teacher's class list: every enabled learner in the pilot cohort */
    @GetMapping("/learners")
    public List<TeacherViews.LearnerRosterView> learners() {
        return users.findEnabledByRole(Role.STUDENT).stream()
                .map(TeacherViews.LearnerRosterView::from)
                .toList();
    }
}
