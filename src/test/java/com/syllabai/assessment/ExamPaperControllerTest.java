package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.sme.SmeQuestionSpecPointRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C28: the exam-paper detail payload speaks the SAME spec-point dialect as
 * the learner question views — every {@code PaperQuestionView} carries rich
 * refs ({@code code + role + applicability}) from the shared T-C24 projection
 * (PRIMARY first, then SECONDARY, each code-ordered), and questions without a
 * curriculum mapping arrive honestly empty rather than fabricated. No new SQL
 * contract exists: the controller reuses {@code ServableQuestionService}.
 */
class ExamPaperControllerTest {

    private static final Map<String, Object> SP_1_1_APPLICABILITY = Map.of(
            "papers", List.of("1C", "2C"),
            "double_award_shared", true,
            "rule", "C-suffixed points are Chemistry-only content (not in Science Double Award)");

    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final SmeQuestionSpecPointRepository specPoints =
            mock(SmeQuestionSpecPointRepository.class);
    private final QuestionTopicRepository topicMappings = mock(QuestionTopicRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);

    private final ServableQuestionService servableQuestions = new ServableQuestionService(
            questions, questionVersions, examPapers, specPoints,
            topicMappings, knowledgeNodes, knowledgeEdges, knowledgeGraph);

    private final ExamPaperController controller =
            new ExamPaperController(examPapers, questions, questionVersions, servableQuestions);

    private final UUID paperId = UUID.randomUUID();
    private final UUID q1Id = UUID.randomUUID();
    private final UUID q2Id = UUID.randomUUID();

    /** built in @BeforeEach — stubbing a mock must never happen inside another stub's thenReturn */
    private ExamPaper paper;

    @org.junit.jupiter.api.BeforeEach
    void buildPaper() {
        paper = paper();
    }

    @Test
    @DisplayName("paper-detail questions carry rich spec-point refs from the shared projection, PRIMARY first")
    void paperDetailCarriesRichRefs() {
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        Question q1 = paperQuestion(q1Id, "1(a)");
        Question q2 = paperQuestion(q2Id, "1(b)");
        when(questions.findAllByExamPaperIdOrderByDifficultyAsc(paperId))
                .thenReturn(List.of(q1, q2));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(any(UUID.class)))
                .thenReturn(List.of());
        when(specPoints.findCodesByQuestionIdsIn(anyCollection())).thenReturn(List.of(
                // deliberately unsorted input: SECONDARY 1.2 before PRIMARY 1.1
                mapping(q1Id, "4CH1-1.2", "SECONDARY", null),
                mapping(q1Id, "4CH1-1.1", "PRIMARY", SP_1_1_APPLICABILITY)));

        ExamPaperController.PaperDetailView detail = controller.get(paperId);

        assertThat(detail.paper().paperCode()).isEqualTo("1C");
        assertThat(detail.questions()).hasSize(2);

        var mapped = detail.questions().get(0);
        assertThat(mapped.specPoints()).hasSize(2);
        assertThat(mapped.specPoints()).extracting(StudentQuestionView.SpecPointRef::code)
                .containsExactly("4CH1-1.1", "4CH1-1.2");
        assertThat(mapped.specPoints().get(0).role()).isEqualTo("PRIMARY");
        assertThat(mapped.specPoints().get(0).applicability())
                .containsEntry("papers", List.of("1C", "2C"))
                .containsEntry("double_award_shared", true)
                .containsEntry("rule",
                        "C-suffixed points are Chemistry-only content (not in Science Double Award)");
        assertThat(mapped.specPoints().get(1).role()).isEqualTo("SECONDARY");
        assertThat(mapped.specPoints().get(1).applicability()).isNull();

        // the unmapped question stays in the payload — honest empty, not dropped
        var unmapped = detail.questions().get(1);
        assertThat(unmapped.specPoints()).isEmpty();
    }

    @Test
    @DisplayName("a paper with no mapped questions at all serves every specPoints list empty")
    void noMappingsServeEmpty() {
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        Question q1 = paperQuestion(q1Id, "2(a)");
        when(questions.findAllByExamPaperIdOrderByDifficultyAsc(paperId))
                .thenReturn(List.of(q1));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(any(UUID.class)))
                .thenReturn(List.of());
        when(specPoints.findCodesByQuestionIdsIn(anyCollection())).thenReturn(List.of());

        ExamPaperController.PaperDetailView detail = controller.get(paperId);

        assertThat(detail.questions()).hasSize(1);
        assertThat(detail.questions().get(0).specPoints()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────

    private ExamPaper paper() {
        ExamPaper paper = mock(ExamPaper.class);
        when(paper.id()).thenReturn(paperId);
        when(paper.subjectId()).thenReturn(UUID.randomUUID());
        when(paper.title()).thenReturn("Chemistry Paper 1C — June 2019");
        when(paper.board()).thenReturn("Edexcel");
        when(paper.qualification()).thenReturn("IGCSE Chemistry");
        when(paper.unit()).thenReturn(null);
        when(paper.sessionLabel()).thenReturn("June 2019");
        when(paper.paperCode()).thenReturn("1C");
        when(paper.validationState()).thenReturn(ExamPaper.ValidationState.VALIDATED);
        when(paper.provenance()).thenReturn(ExamPaper.Provenance.PAST_PAPER);
        when(paper.questionPaperDocumentId()).thenReturn(null);
        when(paper.markSchemeDocumentId()).thenReturn(null);
        return paper;
    }

    private Question paperQuestion(UUID id, String externalRef) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(id);
        when(question.externalRef()).thenReturn(externalRef);
        when(question.marks()).thenReturn(3);
        when(question.provenance()).thenReturn(Question.Provenance.PAST_PAPER);
        return question;
    }

    private SmeQuestionSpecPointRepository.CodeProjection mapping(UUID questionId, String code,
                                                                  String role,
                                                                  Map<String, Object> applicability) {
        return new SmeQuestionSpecPointRepository.CodeProjection() {
            @Override public UUID getQuestionId() {
                return questionId;
            }

            @Override public String getCode() {
                return code;
            }

            @Override public String getRole() {
                return role;
            }

            @Override public Map<String, Object> getApplicability() {
                return applicability;
            }
        };
    }
}
