package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V20 paper-level serving gate: questions under a REJECTED or FLAGGED exam paper
 * never serve, even when their own current version is VALIDATED — the paper
 * state expresses systematic defects per-version validation cannot. Paper-less
 * questions (SEED_DEMO orphans) stay governed by the per-question rule alone.
 */
class ServableQuestionServicePaperGateTest {

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final ServableQuestionService service =
            new ServableQuestionService(questions, questionVersions, examPapers);

    private Question structuredQuestion(UUID paperId) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.STRUCTURED);
        when(question.examPaperId()).thenReturn(paperId);
        return question;
    }

    private QuestionVersion validatedVersion(Question question) {
        QuestionVersion version = mock(QuestionVersion.class);
        when(version.validationState()).thenReturn(QuestionVersion.ValidationState.VALIDATED);
        when(version.parts()).thenReturn(List.of());
        when(questionVersions.findByQuestionIdOrderByVersionDesc(question.id()))
                .thenReturn(List.of(version));
        return version;
    }

    @Test
    @DisplayName("a VALIDATED version under a REJECTED paper does not serve")
    void rejectedPaperBlocksServing() {
        UUID rejectedPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of(rejectedPaper));
        Question question = structuredQuestion(rejectedPaper);
        validatedVersion(question);
        when(questions.findAllActive()).thenReturn(List.of(question));

        assertThat(service.allActive()).isEmpty();
    }

    @Test
    @DisplayName("a VALIDATED version under a FLAGGED paper does not serve (V20)")
    void flaggedPaperBlocksServing() {
        UUID flaggedPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of(flaggedPaper));
        Question question = structuredQuestion(flaggedPaper);
        validatedVersion(question);
        when(questions.findWithOptions(question.id())).thenReturn(Optional.of(question));

        assertThat(service.findById(question.id())).isEmpty();
    }

    @Test
    @DisplayName("a VALIDATED version under a healthy paper still serves")
    void healthyPaperServes() {
        UUID healthyPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        Question question = structuredQuestion(healthyPaper);
        QuestionVersion version = validatedVersion(question);
        when(questions.findWithOptions(question.id())).thenReturn(Optional.of(question));

        Optional<StudentQuestionView> view = service.findById(question.id());
        assertThat(view).isPresent();
    }

    @Test
    @DisplayName("paper-less questions (SEED_DEMO orphans) are not gated by paper state")
    void paperlessQuestionsUngated() {
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of(UUID.randomUUID()));
        Question question = structuredQuestion(null);
        validatedVersion(question);
        when(questions.findAllActive()).thenReturn(List.of(question));

        assertThat(service.allActive()).hasSize(1);
    }
}
