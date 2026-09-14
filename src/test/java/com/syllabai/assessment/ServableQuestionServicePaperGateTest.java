package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
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
 *
 * <p>List paths (allActive/activeWithin/activeByTopic) fetch versions through the
 * batched {@code findWithPartsByQuestionIdsIn} — one query for the whole page —
 * so list-shaped tests stub that method while single-question paths keep the
 * per-question lookup.</p>
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

    /** stub the batched list-path fetch: one VALIDATED current version for the question */
    private QuestionVersion batchedValidatedVersion(Question question) {
        UUID questionId = question.id(); // hoisted: never touch another mock mid-stubbing
        QuestionVersion version = mock(QuestionVersion.class);
        when(version.questionId()).thenReturn(questionId);
        when(version.version()).thenReturn(1);
        when(version.validationState()).thenReturn(QuestionVersion.ValidationState.VALIDATED);
        when(version.parts()).thenReturn(List.of());
        when(questionVersions.findWithPartsByQuestionIdsIn(anyCollection()))
                .thenReturn(List.of(version));
        return version;
    }

    @Test
    @DisplayName("a VALIDATED version under a REJECTED paper does not serve")
    void rejectedPaperBlocksServing() {
        UUID rejectedPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of(rejectedPaper));
        Question question = structuredQuestion(rejectedPaper);
        batchedValidatedVersion(question);
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
        batchedValidatedVersion(question);
        when(questions.findAllActive()).thenReturn(List.of(question));

        assertThat(service.allActive()).hasSize(1);
    }

    @Test
    @DisplayName("batched list path serves only the CURRENT (highest) version's state")
    void batchedPathUsesHighestVersion() {
        UUID healthyPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        Question question = structuredQuestion(healthyPaper);
        UUID questionId = question.id(); // hoisted: never touch another mock mid-stubbing

        QuestionVersion v2Suggested = mock(QuestionVersion.class);
        when(v2Suggested.questionId()).thenReturn(questionId);
        when(v2Suggested.version()).thenReturn(2);
        when(v2Suggested.validationState()).thenReturn(QuestionVersion.ValidationState.SUGGESTED);
        when(v2Suggested.parts()).thenReturn(List.of());
        QuestionVersion v1Validated = mock(QuestionVersion.class);
        when(v1Validated.questionId()).thenReturn(questionId);
        when(v1Validated.version()).thenReturn(1);
        when(v1Validated.validationState()).thenReturn(QuestionVersion.ValidationState.VALIDATED);
        when(v1Validated.parts()).thenReturn(List.of());
        // out of order on purpose: the service must pick max version, not first
        when(questionVersions.findWithPartsByQuestionIdsIn(anyCollection()))
                .thenReturn(List.of(v1Validated, v2Suggested));
        when(questions.findAllActive()).thenReturn(List.of(question));

        // current version (v2) is SUGGESTED -> never serves, even though v1 was VALIDATED
        assertThat(service.allActive()).isEmpty();
    }
}
