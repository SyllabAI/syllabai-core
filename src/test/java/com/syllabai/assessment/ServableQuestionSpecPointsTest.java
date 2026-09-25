package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C24: the learner question view carries its spec-point mappings as RICH
 * refs — code + mapping role + the spec point's official applicability
 * (verbatim papers / double_award_shared / rule for 4CH1) — while the bare
 * specPointCodes list stays derived (same order, same codes) so the ADR-026
 * consumers can never drift from the rich view. Ordering contract is the
 * ADR-026 one: PRIMARY first, then SECONDARY, each code-ordered.
 */
class ServableQuestionSpecPointsTest {

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final SmeQuestionSpecPointRepository specPoints =
            mock(SmeQuestionSpecPointRepository.class);
    private final QuestionTopicRepository topicMappings = mock(QuestionTopicRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
    private final ServableQuestionService service =
            new ServableQuestionService(questions, questionVersions, examPapers, specPoints,
                    topicMappings, knowledgeNodes, knowledgeEdges, knowledgeGraph);

    private static final Map<String, Object> SP_1_1_APPLICABILITY = Map.of(
            "papers", List.of("1C", "2C"),
            "double_award_shared", true,
            "rule", "C-suffixed points are Chemistry-only content (not in Science Double Award)");

    private Question mcq() {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.MCQ_SINGLE);
        when(question.examPaperId()).thenReturn(null);
        when(question.options()).thenReturn(List.of());
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

    @Test
    @DisplayName("the view carries rich refs (code, role, applicability) with codes derived, PRIMARY first")
    void viewCarriesRichRefs() {
        Question q = mcq();
        UUID id = q.id();
        when(questions.findAllActive()).thenReturn(List.of(q));
        when(specPoints.findCodesByQuestionIdsIn(anyCollection())).thenReturn(List.of(
                // deliberately unsorted input: SECONDARY 1.2 before PRIMARY 1.1
                mapping(id, "4CH1-1.2", "SECONDARY", null),
                mapping(id, "4CH1-1.1", "PRIMARY", SP_1_1_APPLICABILITY)));

        List<StudentQuestionView> views = service.allActive();

        assertThat(views).hasSize(1);
        StudentQuestionView view = views.get(0);
        assertThat(view.specPointCodes()).containsExactly("4CH1-1.1", "4CH1-1.2");
        assertThat(view.specPoints()).hasSize(2);
        assertThat(view.specPoints().get(0).code()).isEqualTo("4CH1-1.1");
        assertThat(view.specPoints().get(0).role()).isEqualTo("PRIMARY");
        assertThat(view.specPoints().get(0).applicability())
                .containsEntry("papers", List.of("1C", "2C"))
                .containsEntry("double_award_shared", true);
        assertThat(view.specPoints().get(1).code()).isEqualTo("4CH1-1.2");
        assertThat(view.specPoints().get(1).role()).isEqualTo("SECONDARY");
        assertThat(view.specPoints().get(1).applicability()).isNull();
    }

    @Test
    @DisplayName("a question without mappings keeps both spec-point fields honestly empty")
    void noMappingsStayEmpty() {
        Question q = mcq();
        when(questions.findAllActive()).thenReturn(List.of(q));
        when(specPoints.findCodesByQuestionIdsIn(anyCollection())).thenReturn(List.of());

        List<StudentQuestionView> views = service.allActive();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).specPointCodes()).isEmpty();
        assertThat(views.get(0).specPoints()).isEmpty();
    }
}
