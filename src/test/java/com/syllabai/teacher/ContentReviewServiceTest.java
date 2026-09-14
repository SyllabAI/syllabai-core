package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The teacher review read model must expose the full answer key (§7: a reviewer
 * validates WHAT they see) while remaining a read-only projection — the serving
 * boundary itself is untouched.
 */
class ContentReviewServiceTest {

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);
    private final ContentReviewService service =
            new ContentReviewService(examPapers, questionVersions, markSchemes, markPoints);

    @Test
    @DisplayName("paperReview carries content + answer key + scheme state per version")
    void paperReviewExposesAnswerKey() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.title()).thenReturn("Chemistry 1C");
        when(paper.paperCode()).thenReturn("4CH1/1C");
        when(paper.sessionLabel()).thenReturn("June 2024");
        when(paper.board()).thenReturn("Edexcel");
        when(paper.qualification()).thenReturn("IGCSE");
        when(paper.validationState()).thenReturn(ExamPaper.ValidationState.SUGGESTED);
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));

        UUID versionId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        UUID correctOptionId = UUID.randomUUID();

        Question question = mock(Question.class);
        when(question.id()).thenReturn(questionId);
        when(question.externalRef()).thenReturn("q01");
        when(question.type()).thenReturn(Question.Type.MCQ_SINGLE);
        when(question.stem()).thenReturn("Which gas is produced?");
        when(question.marks()).thenReturn(1);
        QuestionOption distractor = mock(QuestionOption.class);
        when(distractor.id()).thenReturn(UUID.randomUUID());
        when(distractor.label()).thenReturn("A");
        when(distractor.text()).thenReturn("Oxygen");
        when(distractor.correct()).thenReturn(false);
        when(distractor.misconceptionNodeId()).thenReturn(UUID.randomUUID());
        QuestionOption correct = mock(QuestionOption.class);
        when(correct.id()).thenReturn(correctOptionId);
        when(correct.label()).thenReturn("B");
        when(correct.text()).thenReturn("Hydrogen");
        when(correct.correct()).thenReturn(true);
        when(correct.misconceptionNodeId()).thenReturn(null);
        when(question.options()).thenReturn(List.of(distractor, correct));

        QuestionVersion version = mock(QuestionVersion.class);
        when(version.id()).thenReturn(versionId);
        when(version.question()).thenReturn(question);
        when(version.version()).thenReturn(1);
        when(version.marks()).thenReturn(0);
        when(version.stem()).thenReturn("Which gas forms at the cathode?");
        when(version.validationState()).thenReturn(QuestionVersion.ValidationState.SUGGESTED);
        when(version.parts()).thenReturn(List.of());
        when(questionVersions.findByPaperId(paperId)).thenReturn(List.of(version));

        MarkPoint point = new MarkPoint(null, null, "a", 0,
                "hydrogen at the cathode", 1, List.of("answer names hydrogen"), null);
        MarkScheme scheme = mock(MarkScheme.class);
        when(scheme.id()).thenReturn(schemeId);
        when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.SUGGESTED);
        when(scheme.points()).thenReturn(List.of(point));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(versionId))
                .thenReturn(Optional.of(scheme));

        ContentReviewService.PaperReviewView view = service.paperReview(paperId);

        assertThat(view.paper().id()).isEqualTo(paperId);
        assertThat(view.versions()).hasSize(1);
        ContentReviewService.VersionReviewView v = view.versions().get(0);
        assertThat(v.versionId()).isEqualTo(versionId);
        assertThat(v.stem()).isEqualTo("Which gas forms at the cathode?");   // version stem wins
        assertThat(v.validationState()).isEqualTo("SUGGESTED");
        assertThat(v.schemeId()).isEqualTo(schemeId);
        assertThat(v.schemeState()).isEqualTo("SUGGESTED");
        assertThat(v.options()).extracting("correct").containsExactly(false, true);
        assertThat(v.options()).extracting("text").containsExactly("Oxygen", "Hydrogen");
        assertThat(v.points()).hasSize(1);
        assertThat(v.points().get(0).acceptanceCriteria()).containsExactly("answer names hydrogen");
    }

    @Test
    @DisplayName("paperReview 404s for an unknown paper")
    void paperReviewNotFound() {
        UUID missing = UUID.randomUUID();
        when(examPapers.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.paperReview(missing))
                .isInstanceOf(NotFoundException.class);
    }
}
