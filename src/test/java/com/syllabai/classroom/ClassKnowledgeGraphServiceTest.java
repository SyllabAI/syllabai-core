package com.syllabai.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.learner.LearnerKnowledgeGraphService;
import com.syllabai.learner.MisconceptionStateRepository;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the TFA-07 §14 gate INSIDE the service (the controller's
 * ownership gate is the ClassKnowledgeGraphControllerTest's job): the roster
 * is the privacy boundary — a non-member learner is a 404, a DISABLED member
 * is a 404, and a valid member gets the SAME F-034 read model the student
 * themselves sees (delegation pin: no second graph implementation).
 */
class ClassKnowledgeGraphServiceTest {

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final MisconceptionStateRepository misconceptionStates =
            mock(MisconceptionStateRepository.class);
    private final TeachingCoverageRepository coverage = mock(TeachingCoverageRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EbbinghausDecayService decayService = mock(EbbinghausDecayService.class);
    private final LearnerProperties learnerProperties = mock(LearnerProperties.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final LearnerKnowledgeGraphService learnerGraphs =
            mock(LearnerKnowledgeGraphService.class);

    private final ClassKnowledgeGraphService service = new ClassKnowledgeGraphService(
            graph, skillStates, misconceptionStates, coverage, members, users,
            decayService, learnerProperties, attempts, learnerGraphs);

    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ROOT = UUID.randomUUID();

    @Test
    @DisplayName("a non-member learner is a 404 — the roster is the privacy boundary")
    void nonMemberIs404() {
        SchoolClass clazz = clazz();
        when(members.existsByClassIdAndStudentId(CLASS_ID, LEARNER)).thenReturn(false);

        assertThatThrownBy(() -> service.learnerKnowledgeGraph(clazz, LEARNER, ROOT))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("a DISABLED member is a 404 — the enabled gate matches the aggregation roster")
    void disabledMemberIs404() {
        SchoolClass clazz = clazz();
        when(members.existsByClassIdAndStudentId(CLASS_ID, LEARNER)).thenReturn(true);
        User disabled = mock(User.class);
        when(disabled.enabled()).thenReturn(false);
        when(users.findById(LEARNER)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.learnerKnowledgeGraph(clazz, LEARNER, ROOT))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("an enabled member gets the SAME F-034 read model — delegation, not a second graph")
    void enabledMemberDelegatesToF034() {
        SchoolClass clazz = clazz();
        when(members.existsByClassIdAndStudentId(CLASS_ID, LEARNER)).thenReturn(true);
        User enabled = mock(User.class);
        when(enabled.enabled()).thenReturn(true);
        when(users.findById(LEARNER)).thenReturn(Optional.of(enabled));
        LearnerKnowledgeGraphView view = new LearnerKnowledgeGraphView(
                LEARNER, ROOT, "4CH1", "Chemistry", Instant.now(), List.of(), List.of());
        when(learnerGraphs.graphFor(LEARNER, ROOT)).thenReturn(view);

        assertThat(service.learnerKnowledgeGraph(clazz, LEARNER, ROOT)).isSameAs(view);
    }

    private SchoolClass clazz() {
        return new SchoolClass(UUID.randomUUID(), "igcse-chemistry-19",
                "IGCSE Chemistry", "10A") {
            @Override
            public UUID id() {
                return CLASS_ID;
            }
        };
    }
}
