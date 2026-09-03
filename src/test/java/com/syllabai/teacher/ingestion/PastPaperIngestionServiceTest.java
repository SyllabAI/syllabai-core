package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.shared.ConflictException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-011 ingestion bridge: a parser draft lands as an all-SUGGESTED paper with
 * structured questions, parts and part-scoped mark points; duplicate ingestion
 * is rejected; drafts without content are rejected.
 */
class PastPaperIngestionServiceTest {

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final CurriculumVersionRepository curriculumVersions =
            mock(CurriculumVersionRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);

    private final PastPaperIngestionService service = new PastPaperIngestionService(
            examPapers, questions, questionVersions, markSchemes, subjects,
            curriculumVersions, knowledgeNodes, knowledgeEdges);

    private final List<Object> savedAll = new ArrayList<>();

    {
        when(examPapers.save(any(ExamPaper.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(questions.save(any(Question.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(markSchemes.save(any(MarkScheme.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(knowledgeNodes.save(any(KnowledgeNode.class)))
                .thenAnswer(inv -> capture(TestIds.withId(inv.getArgument(0), UUID.randomUUID())));
        when(curriculumVersions.save(any(CurriculumVersion.class)))
                .thenAnswer(inv -> capture(inv.getArgument(0)));
        when(subjects.save(any(Subject.class))).thenAnswer(inv -> capture(inv.getArgument(0)));
        when(examPapers.findByPaperCodeAndSessionLabel(any(), any())).thenReturn(Optional.empty());
        when(subjects.findByCode(any())).thenReturn(Optional.empty());
        when(curriculumVersions.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(questionVersions.save(any(com.syllabai.assessment.QuestionVersion.class)))
                .thenAnswer(inv -> capture(inv.getArgument(0)));
    }

    private <T> T capture(T entity) {
        savedAll.add(entity);
        return entity;
    }

    private PastPaperDraftDto draft() {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry", "Paper 1C",
                        "January 2012", "4CH0/1C", "qp-doc-1", "ms-doc-1"),
                List.of(
                        new PastPaperDraftDto.QuestionDraft("q3", "3",
                                "Question 3 stem about rusting", null, 3, "STRUCTURED",
                                2, 0.55,
                                List.of(
                                        new PastPaperDraftDto.PartDraft("a", "What is rust?",
                                                "State", 1, 0.5),
                                        new PastPaperDraftDto.PartDraft("b",
                                                "Explain galvanising.", "Explain", 2, 0.5)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "ms-doc-1", List.of(
                        new PastPaperDraftDto.MarkPointDraft("3-a", 1, "iron(III) oxide", 1,
                                List.of(), 0.55),
                        new PastPaperDraftDto.MarkPointDraft("3-b", 1, "zinc layer prevents air contact", 1,
                                List.of(), 0.55),
                        new PastPaperDraftDto.MarkPointDraft("9", 1, "question-level orphan point", 1,
                                List.of(), 0.55))),
                "opendataloader-fast+heuristics-v0",
                true);
    }

    @Test
    @DisplayName("draft lands as one SUGGESTED paper with parts and part-scoped mark points")
    void ingestCreatesSuggestedContent() {
        PastPaperIngestionService.IngestionSummary summary =
                service.ingest(draft(), UUID.randomUUID());

        assertThat(summary.questions()).isEqualTo(1);
        assertThat(summary.parts()).isEqualTo(2);
        assertThat(summary.markPoints()).isEqualTo(2);   // "9" belongs to no question here

        ExamPaper saved = (ExamPaper) savedAll.stream()
                .filter(e -> e instanceof ExamPaper).findFirst().orElseThrow();
        assertThat(saved.validationState()).isEqualTo(ExamPaper.ValidationState.SUGGESTED);
        assertThat(saved.provenance()).isEqualTo(ExamPaper.Provenance.PAST_PAPER);
        assertThat(saved.questionPaperDocumentId()).isEqualTo("qp-doc-1");

        Question question = (Question) savedAll.stream()
                .filter(e -> e instanceof Question).findFirst().orElseThrow();
        assertThat(question.type()).isEqualTo(Question.Type.STRUCTURED);
        assertThat(question.examPaperId()).isEqualTo(saved.id());
        assertThat(question.provenance()).isEqualTo(Question.Provenance.PAST_PAPER);

        MarkScheme scheme = (MarkScheme) savedAll.stream()
                .filter(e -> e instanceof MarkScheme).findFirst().orElseThrow();
        assertThat(scheme.validationState()).isEqualTo(MarkScheme.ValidationState.SUGGESTED);
        assertThat(scheme.points()).hasSize(2);
        assertThat(scheme.points().get(0).questionPart()).isNotNull();   // "3-a" -> part "a"
        assertThat(scheme.points().get(0).questionPart().label()).isEqualTo("a");
        assertThat(scheme.points().get(1).questionPart().label()).isEqualTo("b");
    }

    @Test
    @DisplayName("re-ingesting the same paper+session is rejected")
    void duplicateRejected() {
        when(examPapers.findByPaperCodeAndSessionLabel("4CH0/1C", "January 2012"))
                .thenReturn(Optional.of(new ExamPaper(
                        UUID.randomUUID(), "t", "b", "q", null, null, null, null, null,
                        ExamPaper.Provenance.PAST_PAPER, null, null)));

        assertThatThrownBy(() -> service.ingest(draft(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already ingested");
    }

    @Test
    @DisplayName("a draft with no questions is rejected")
    void emptyDraftRejected() {
        PastPaperDraftDto empty = new PastPaperDraftDto("1.0",
                draft().paper(), List.of(), null, "m", true);
        assertThatThrownBy(() -> service.ingest(empty, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no questions");
    }
}
