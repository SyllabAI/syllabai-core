package com.syllabai.assessment;

import com.syllabai.assessment.dto.StudentQuestionView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exam-paper browsing (Master Spec §6.5, §22). Any authenticated user may list/view;
 * serving individual questions remains the questions endpoints' responsibility.
 *
 * <p>Since T-C28 each paper question also carries its spec-point refs
 * ({@code code + role + applicability}) from the SAME projection the learner
 * question views use — the papers detail payload can therefore scope itself
 * against the official assessment structure exactly like the topic lists,
 * with the same honest-absent semantics (no mapping → empty list).</p>
 */
@RestController
@RequestMapping("/api/v1/exam-papers")
public class ExamPaperController {

    private final ExamPaperRepository examPapers;
    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final ServableQuestionService servableQuestions;

    public ExamPaperController(ExamPaperRepository examPapers,
                               QuestionRepository questions,
                               QuestionVersionRepository questionVersions,
                               ServableQuestionService servableQuestions) {
        this.examPapers = examPapers;
        this.questions = questions;
        this.questionVersions = questionVersions;
        this.servableQuestions = servableQuestions;
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
        Map<UUID, List<StudentQuestionView.SpecPointRef>> refs = servableQuestions
                .specPointRefs(paperQuestions.stream().map(Question::id).toList());
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
                            latest == null ? null : latest.id(),
                            // honest absence: questions without a curriculum
                            // mapping simply carry an empty list (T-C28)
                            refs.getOrDefault(q.id(), List.of()));
                }).toList());
    }

    public record PaperView(UUID id, UUID subjectId, String title, String board,
                            String qualification, String unit, String sessionLabel,
                            String paperCode, String validationState, String provenance,
                            String questionPaperDocumentId, String markSchemeDocumentId) {

        public static PaperView from(ExamPaper p) {
            return new PaperView(p.id(), p.subjectId(), p.title(), p.board(), p.qualification(),
                    p.unit(), p.sessionLabel(), p.paperCode(), p.validationState().name(),
                    p.provenance().name(), p.questionPaperDocumentId(),
                    p.markSchemeDocumentId());
        }
    }

    public record PaperDetailView(PaperView paper, List<PaperQuestionView> questions) {
    }

    public record PaperQuestionView(UUID questionId, String externalRef, int marks,
                                    String provenance, String versionValidationState,
                                    int partCount, UUID currentVersionId,
                                    List<StudentQuestionView.SpecPointRef> specPoints) {
    }
}
