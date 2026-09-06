package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * T-029 class roster: the teacher's minimal "class list" is the enabled
 * STUDENT cohort (the pilot has no class entity). The projection exposes
 * identity fields only — no password hash, no role set, no learning data.
 */
class TeacherRosterControllerTest {

    private final UserRepository users = Mockito.mock(UserRepository.class);
    private final TeacherRosterController controller = new TeacherRosterController(users);

    private static User user(String email, String name) throws ReflectiveOperationException {
        User user = new User(email, "bcrypt-hash", name, Set.of(Role.STUDENT));
        TestIds.withId(user, UUID.randomUUID());
        // @PrePersist never runs in unit tests, so createdAt is set explicitly
        java.lang.reflect.Field field = User.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(user, Instant.now());
        return user;
    }

    @Test
    @DisplayName("roster projects enabled STUDENT users ordered as the repository returns them")
    void rosterProjection() throws ReflectiveOperationException {
        User ada = user("ada@example.com", "Ada Learner");
        User second = user("second@example.com", "Second Learner");
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of(ada, second));

        var roster = controller.learners();

        assertThat(roster).hasSize(2);
        assertThat(roster.get(0).id()).isEqualTo(ada.id());
        assertThat(roster.get(0).displayName()).isEqualTo("Ada Learner");
        assertThat(roster.get(0).email()).isEqualTo("ada@example.com");
        assertThat(roster.get(0).createdAt()).isNotNull();
        // identity projection only — the DTO record has exactly 4 components,
        // so password hashes / roles cannot leak through the API boundary
        assertThat(roster.get(0).getClass().getRecordComponents()).hasSize(4);
        verify(users).findEnabledByRole(Role.STUDENT);
    }

    @Test
    @DisplayName("empty cohort renders an empty roster, not an error")
    void emptyCohort() {
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of());
        assertThat(controller.learners()).isEmpty();
    }

    @Test
    @DisplayName("createdAt comes from the entity, not a projection default")
    void createdAtIsReal() throws ReflectiveOperationException {
        User user = user("x@example.com", "X");
        Instant before = Instant.now().minusSeconds(60);
        java.lang.reflect.Field field = User.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(user, before);
        when(users.findEnabledByRole(Role.STUDENT)).thenReturn(List.of(user));

        assertThat(controller.learners().get(0).createdAt()).isEqualTo(before);
    }
}
