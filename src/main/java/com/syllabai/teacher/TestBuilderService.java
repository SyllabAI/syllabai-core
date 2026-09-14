package com.syllabai.teacher;

import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test Builder (P9 smallest-useful-version): assemble a printable topic test
 * from VALIDATED content only. The assembly reuses {@link ServableQuestionService}
 * — the ONE learner-serving boundary — so a generated test can never contain
 * SUGGESTED/FLAGGED/REJECTED content, and the V20 paper gate applies as usual.
 *
 * <p>Scope deliberately minimal: topic selection -> difficulty-ordered
 * question list with marks and parts, optional answer key from the mark
 * schemes (teacher-only surface; scheme validation state is reported so the
 * teacher knows what they are printing). No persistence, no scheduling, no
 * IRT assembly — those are later cycles.</p>
 */
@Service
@Transactional(readOnly = true)
public class TestBuilderService {

    private static final int DEFAULT_MAX = 20;
    private static final int HARD_MAX = 50;

    private final ServableQuestionService servableQuestions;
    private final KnowledgeGraphService knowledgeGraph;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;

    public TestBuilderService(ServableQuestionService servableQuestions,
                              KnowledgeGraphService knowledgeGraph,
                              QuestionVersionRepository questionVersions,
                              MarkSchemeRepository markSchemes) {
        this.servableQuestions = servableQuestions;
        this.knowledgeGraph = knowledgeGraph;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
    }

    /**
     * @param rootId        the subject's KG root — hard scope: only topics inside
     *                      its PART_OF subtree are honoured (subject isolation)
     * @param topicNodeIds  selected curriculum topics
     * @param maxQuestions  cap on assembled questions (clamped 1..50, default 20)
     * @param includeAnswers attach the current mark scheme's points per question
     */
    public TestPreviewView preview(UUID rootId, List<UUID> topicNodeIds,
                                   Integer maxQuestions, boolean includeAnswers) {
        Set<UUID> subtree = new HashSet<>(knowledgeGraph.subtreeIds(rootId));
        List<UUID> topics = topicNodeIds == null ? List.of()
                : topicNodeIds.stream().filter(subtree::contains).distinct().toList();

        // topic code lookup for attribution + coverage summary
        Map<UUID, String> codeByTopic = new HashMap<>();
        Map<UUID, String> titleByTopic = new HashMap<>();
        for (UUID topic : topics) {
            var node = knowledgeGraph.node(topic);
            codeByTopic.put(topic, node.code());
            titleByTopic.put(topic, node.title());
        }

        // assemble through the serving boundary; first selected topic wins attribution
        LinkedHashMap<UUID, StudentQuestionView> byQuestion = new LinkedHashMap<>();
        Map<UUID, UUID> topicByQuestion = new HashMap<>();
        for (UUID topic : topics) {
            for (StudentQuestionView q : servableQuestions.activeByTopic(topic)) {
                if (byQuestion.putIfAbsent(q.id(), q) == null) {
                    topicByQuestion.put(q.id(), topic);
                }
            }
        }

        int cap = maxQuestions == null ? DEFAULT_MAX
                : Math.max(1, Math.min(HARD_MAX, maxQuestions));
        List<StudentQuestionView> selected = byQuestion.values().stream()
                .sorted(Comparator.comparingInt(StudentQuestionView::difficulty)
                        .thenComparing(StudentQuestionView::id))
                .limit(cap)
                .toList();

        // per-topic availability BEFORE the cap (the teacher needs the honest number)
        Map<UUID, Integer> availableByTopic = new LinkedHashMap<>();
        for (UUID topic : topics) {
            availableByTopic.put(topic, servableQuestions.countServableByTopic(topic));
        }

        int totalMarks = selected.stream().mapToInt(StudentQuestionView::marks).sum();

        List<TestQuestionView> questions = new ArrayList<>(selected.size());
        for (StudentQuestionView q : selected) {
            UUID topic = topicByQuestion.get(q.id());
            List<TestAnswerView> answers = List.of();
            String schemeState = null;
            if (includeAnswers && "STRUCTURED".equals(q.type())) {
                var current = questionVersions
                        .findByQuestionIdOrderByVersionDesc(q.id()).stream().findFirst().orElse(null);
                if (current != null) {
                    MarkScheme scheme = markSchemes
                            .findFirstByQuestionVersionIdOrderByCreatedAtDesc(current.id()).orElse(null);
                    if (scheme != null) {
                        schemeState = scheme.validationState().name();
                        answers = scheme.points().stream()
                                .map(p -> new TestAnswerView(
                                        p.questionPart() == null ? null : p.questionPart().label(),
                                        p.ref(), p.text(), p.marks(), p.acceptanceCriteria()))
                                .toList();
                    }
                }
            }
            questions.add(new TestQuestionView(
                    q.id(), q.type(), q.stem(), q.marks(), q.commandWord(), q.difficulty(),
                    codeByTopic.getOrDefault(topic, null),
                    q.parts(), q.options(), answers, schemeState));
        }

        List<TopicCoverage> coverage = topics.stream()
                .map(t -> new TopicCoverage(
                        t, codeByTopic.get(t), titleByTopic.get(t),
                        availableByTopic.getOrDefault(t, 0)))
                .toList();

        return new TestPreviewView(rootId, selected.size(), totalMarks, coverage, questions);
    }

    /** the assembled test: validated questions only, difficulty-ordered */
    public record TestPreviewView(
            UUID rootId, int questionCount, int totalMarks,
            List<TopicCoverage> topics, List<TestQuestionView> questions) {
    }

    public record TopicCoverage(UUID topicNodeId, String code, String title, int servableQuestions) {
    }

    /** print-shaped question: body + parts, answers only when requested */
    public record TestQuestionView(
            UUID id, String type, String stem, int marks, String commandWord, int difficulty,
            String topicCode, List<StudentQuestionView.PartView> parts,
            List<StudentQuestionView.OptionView> options,
            List<TestAnswerView> answers, String schemeState) {
    }

    /** one mark point of the answer key (teacher-only) */
    public record TestAnswerView(String partLabel, String ref, String text, int marks,
                                 List<String> acceptanceCriteria) {
    }
}
