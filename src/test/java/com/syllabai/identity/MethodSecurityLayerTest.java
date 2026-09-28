package com.syllabai.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.AttemptController;
import com.syllabai.cla.ClaController;
import com.syllabai.assessment.QuestionController;
import com.syllabai.content.RoutingController;
import com.syllabai.content.ContentDocumentController;
import com.syllabai.curriculum.CurriculumController;
import com.syllabai.infrastructure.llm.LlmAdminController;
import com.syllabai.intervention.InterventionRunController;
import com.syllabai.recommendation.LearnerRecommendationController;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.revisionnotes.RevisionNoteAdminController;
import com.syllabai.revisionnotes.RevisionNoteLearnerController;
import com.syllabai.smartmark.StudentSmartMarkController;
import com.syllabai.sme.SmeQuestionAdminController;
import com.syllabai.teacher.ClassAnalyticsController;
import com.syllabai.teacher.ContentController;
import com.syllabai.teacher.GlmOcrIngestionController;
import com.syllabai.teacher.TeacherConceptGraphController;
import com.syllabai.teacher.TeacherCurriculumController;
import com.syllabai.teacher.TeacherMarkingController;
import com.syllabai.teacher.TeacherRosterController;
import com.syllabai.teacher.TestBuilderController;
import com.syllabai.tutor.TutorController;
import com.syllabai.tutor.TutorSessionController;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Deep-audit 09-28 M5: SecurityConfig's javadoc documents a method-level
 * role-enforcement layer, {@code @EnableMethodSecurity} is active — but no
 * controller carried the annotation, so the route matchers were the ONLY
 * gate. This pins the restored layer:
 *
 * <ul>
 *   <li>every admin/teacher controller carries {@code @PreAuthorize} with the
 *       EXACT expression its route rule uses (pure defense in depth — a future
 *       route change that drops a matcher still hits the method layer);</li>
 *   <li>learner/auth surfaces deliberately carry none — they are
 *       authenticated-any-role at the chain and identity-scoped in the
 *       handler; a role annotation here would silently tighten semantics;</li>
 *   <li>{@code @EnableMethodSecurity} stays on the configuration — without it
 *       every annotation above would be dead code.</li>
 * </ul>
 */
class MethodSecurityLayerTest {

    private static void assertExpression(Class<?> controller, String expected) {
        PreAuthorize annotation = controller.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("%s must carry the M5 method-security layer", controller.getSimpleName())
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(expected);
    }

    private static void assertNoAnnotation(Class<?> controller) {
        assertThat(controller.getAnnotation(PreAuthorize.class))
                .as("%s must stay WITHOUT a role annotation (chain-governed, "
                        + "authenticated-any-role surface)", controller.getSimpleName())
                .isNull();
    }

    @Test
    @DisplayName("M5: admin controllers require ADMIN at the method layer")
    void adminControllers() {
        String expected = "hasRole('ADMIN')";
        assertExpression(SmeQuestionAdminController.class, expected);
        assertExpression(LlmAdminController.class, expected);
        assertExpression(RevisionNoteAdminController.class, expected);
    }

    @Test
    @DisplayName("M5: teacher controllers require TEACHER or ADMIN at the method layer")
    void teacherControllers() {
        String expected = "hasAnyRole('TEACHER','ADMIN')";
        assertExpression(TeacherConceptGraphController.class, expected);
        assertExpression(ContentController.class, expected);
        assertExpression(TeacherCurriculumController.class, expected);
        assertExpression(TestBuilderController.class, expected);
        assertExpression(TeacherRosterController.class, expected);
        assertExpression(GlmOcrIngestionController.class, expected);
        assertExpression(ClassAnalyticsController.class, expected);
        assertExpression(TeacherMarkingController.class, expected);
        assertExpression(RoutingController.class, expected);
        assertExpression(ContentDocumentController.class, expected);
    }

    @Test
    @DisplayName("M5: learner/auth surfaces stay deliberately unannotated")
    void learnerSurfacesUntouched() {
        List<Class<?>> learnerSurfaces = List.of(
                ClaController.class,
                TutorController.class,
                TutorSessionController.class,
                AttemptController.class,
                QuestionController.class,
                CurriculumController.class,
                InterventionRunController.class,
                LearnerRecommendationController.class,
                LearnerStateController.class,
                RevisionNoteLearnerController.class,
                StudentSmartMarkController.class);
        learnerSurfaces.forEach(MethodSecurityLayerTest::assertNoAnnotation);
    }

    @Test
    @DisplayName("M5: @EnableMethodSecurity is active — the annotations are live")
    void methodSecurityIsEnabled() {
        assertThat(SecurityConfig.class
                .isAnnotationPresent(EnableMethodSecurity.class))
                .as("SecurityConfig must keep @EnableMethodSecurity or every "
                        + "@PreAuthorize above becomes dead code")
                .isTrue();
    }
}
