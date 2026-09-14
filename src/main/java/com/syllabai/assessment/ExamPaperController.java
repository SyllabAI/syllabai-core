package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exam-paper browsing (Master Spec §6.5, §22). Any authenticated user may list/view;
 * serving individual questions remains the questions endpoints' responsibility.
 */
@RestController
@RequestMapping("/api/v1/exam-papers")
public class ExamPaperController {

    private final ExamPaperRepository examPapers;
    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;

    public ExamPaperController(ExamPaperRepository examPapers,
                               QuestionRepository questions,
                               QuestionVersionRepository questionVersions) {
        this.examPapers = examPapers;
        this.questions = questions;
        this.questionVersions = questionVersions;
    }

    @GetMapping
    public List<PaperView> list(@RequestParam(required = false) UUID subjectId) {
        return (subjectId == null
                ? examPapers.findAllByOrderByCreatedAtDesc()
                : examPapers.findAllBySubjectIdOrderByCreatedAtDesc(subjectId))
                .stream().map(PaperView::from).toList();
    }

    @GetMapping("/{id}")
    public PaperDetailView get(@PathVariable UUID id) {
        ExamPaper paper = examPapers.findById(id)
                .orElseThrow(() -> new com.syllabai.shared.NotFoundException("exam paper", id));
        List<Question> paperQuestions =
                questions.findAllByExamPaperIdOrderByDifficultyAsc(id);
        return new PaperDetailView(
                PaperView.from(paper),
                paperQuestions.stream().map(q -> {
                    QuestionVersion latest = questionVersions
                            .findByQuestionIdOrderByVersionDesc(q.id()).stream()
                            .findFirst().orElse(null);
                    return new PaperQuestionView(
                            q.id(), q.externalRef(), q.marks(),
                            q.provenance().name(),
                            latest == null ? null : latest.validationState().name(),
                            latest == null ? 0 : latest.parts().size(),
                            latest == null ? null : latest.id());
                }).toList());
    }

    public record PaperView(UUID id, String title, String board, String qualification,
                            String unit, String sessionLabel, String paperCode,
                            String validationState, String provenance,
                            String questionPaperDocumentId, String markSchemeDocumentId) {

        public static PaperView from(ExamPaper p) {
            return new PaperView(p.id(), p.title(), p.board(), p.qualification(), p.unit(),
                    p.sessionLabel(), p.paperCode(), p.validationState().name(),
                    p.provenance().name(), p.questionPaperDocumentId(),
                    p.markSchemeDocumentId());
        }
    }

    public record PaperDetailView(PaperView paper, List<PaperQuestionView> questions) {
    }

    public record PaperQuestionView(UUID questionId, String externalRef, int marks,
                                    String provenance, String versionValidationState,
                                    int partCount, UUID currentVersionId) {
    }
}
