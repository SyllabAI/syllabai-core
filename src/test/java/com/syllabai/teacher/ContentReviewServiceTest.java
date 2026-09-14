package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecord;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The teacher review read model must expose the full answer key (§7: a reviewer
 * validates WHAT they see) while remaining a read-only projection — the serving
 * boundary itself is untouched. V20 adds the flag/unflag lifecycle, batch
 * validate-all guards and the enriched queue.
 */
class ContentReviewServiceTest {

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final GlmOcrBridgeRecordRepository bridgeRecords =
            mock(GlmOcrBridgeRecordRepository.class);
    private final ContentReviewService service =
            new ContentReviewService(examPapers, questionVersions, markSchemes, markPoints,
                    subjects, bridgeRecords);

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

    // ── §7 placement ────────────────────────────────────────────────────────

    @Test
    @DisplayName("placePaper moves a paper into the target subject (factual association only)")
    void placePaperMovesSubject() {
        UUID paperId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.paperCode()).thenReturn("4CH0/1C");
        when(paper.sessionLabel()).thenReturn("June 2011");
        when(paper.subjectId()).thenReturn(UUID.randomUUID()); // currently in the placeholder
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        Subject subject = mock(Subject.class);
        when(subject.code()).thenReturn("4CH1");
        when(subject.name()).thenReturn("Chemistry (4CH1)");
        when(subjects.findById(subjectId)).thenReturn(Optional.of(subject));

        ExamPaper placed = service.placePaper(paperId, subjectId);

        assertThat(placed).isSameAs(paper);
        verify(paper).assignSubject(subjectId);   // association updated...
        verify(paper, never()).assignSubject(null);
    }

    @Test
    @DisplayName("placePaper is idempotent for a paper already in the target subject")
    void placePaperIdempotent() {
        UUID paperId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.subjectId()).thenReturn(subjectId); // already there
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        Subject subject = mock(Subject.class);
        when(subjects.findById(subjectId)).thenReturn(Optional.of(subject));

        assertThat(service.placePaper(paperId, subjectId)).isSameAs(paper);
        verify(paper, never()).assignSubject(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("placePaper 404s for an unknown paper or subject")
    void placePaperNotFound() {
        UUID missingPaper = UUID.randomUUID();
        when(examPapers.findById(missingPaper)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.placePaper(missingPaper, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);

        UUID paperId = UUID.randomUUID();
        UUID missingSubject = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.subjectId()).thenReturn(UUID.randomUUID());
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(subjects.findById(missingSubject)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.placePaper(paperId, missingSubject))
                .isInstanceOf(NotFoundException.class);
    }

    // ── V20: batch validate-all guards ───────────────────────────────────────

    @Test
    @DisplayName("validateAll refuses a REVIEW_REQUIRED import unless forced")
    void validateAllGuardsReviewRequired() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.validationState()).thenReturn(ExamPaper.ValidationState.SUGGESTED);
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        GlmOcrBridgeRecord bridge = mock(GlmOcrBridgeRecord.class);
        when(bridge.reconciliationStatus()).thenReturn("REVIEW_REQUIRED");
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.of(bridge));

        assertThatThrownBy(() -> service.validateAllForPaper(paperId, false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("validateAll refuses a paper with a REJECTED version (reviewer decision is never overwritten)")
    void validateAllGuardsRejectedVersion() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.validationState()).thenReturn(ExamPaper.ValidationState.SUGGESTED);
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.empty());

        QuestionVersion rejected = mock(QuestionVersion.class);
        when(rejected.validationState()).thenReturn(QuestionVersion.ValidationState.REJECTED);
        when(questionVersions.findByPaperId(paperId)).thenReturn(List.of(rejected));

        assertThatThrownBy(() -> service.validateAllForPaper(paperId, true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("REJECTED/FLAGGED");
    }

    @Test
    @DisplayName("validateAll validates every SUGGESTED version + scheme and flips the paper")
    void validateAllHappyPath() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.validationState()).thenReturn(ExamPaper.ValidationState.SUGGESTED);
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.empty());

        // stateful stub: SUGGESTED until validate() flips the holder to VALIDATED
        QuestionVersion v1 = mock(QuestionVersion.class);
        UUID v1Id = UUID.randomUUID();
        when(v1.id()).thenReturn(v1Id);
        final var v1State = new AtomicReference<>(QuestionVersion.ValidationState.SUGGESTED);
        when(v1.validationState()).thenAnswer(inv -> v1State.get());
        doAnswer(inv -> {
            v1State.set(QuestionVersion.ValidationState.VALIDATED);
            return null;
        }).when(v1).validate();
        QuestionVersion v2 = mock(QuestionVersion.class);
        UUID v2Id = UUID.randomUUID();
        when(v2.id()).thenReturn(v2Id);
        when(v2.validationState()).thenReturn(QuestionVersion.ValidationState.VALIDATED);
        when(questionVersions.findByPaperId(paperId)).thenReturn(List.of(v1, v2));

        MarkScheme scheme = mock(MarkScheme.class);
        when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.SUGGESTED);
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(v1Id))
                .thenReturn(Optional.of(scheme));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(v2Id))
                .thenReturn(Optional.empty());

        ContentReviewService.BatchResult result = service.validateAllForPaper(paperId, false);

        verify(v1).validate();                       // SUGGESTED -> VALIDATED
        verify(v2, never()).validate();              // already validated — untouched
        verify(scheme).validate();
        verify(paper).validate();                    // the paper flip
        assertThat(result.totalVersions()).isEqualTo(2);
        assertThat(result.versionsValidated()).isEqualTo(1);
        assertThat(result.schemesValidated()).isEqualTo(1);
    }

    // ── V20: flag / unflag lifecycle ─────────────────────────────────────────

    @Test
    @DisplayName("flag + unflag round-trip a version (unflag lands on SUGGESTED, never VALIDATED)")
    void flagUnflagVersionLifecycle() {
        QuestionVersion version = mock(QuestionVersion.class);
        UUID versionId = UUID.randomUUID();
        when(questionVersions.findById(versionId)).thenReturn(Optional.of(version));

        service.flagQuestionVersion(versionId);
        verify(version).flag();

        service.unflagQuestionVersion(versionId);
        verify(version).unflag();
    }

    @Test
    @DisplayName("flagPaper + unflagPaper delegate to the entity lifecycle (AUDIT-logged)")
    void flagUnflagPaperLifecycle() {
        UUID paperId = UUID.randomUUID();
        ExamPaper paper = mock(ExamPaper.class);
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));

        service.flagPaper(paperId);
        verify(paper).flag();

        service.unflagPaper(paperId);
        verify(paper).unflag();
    }
}
