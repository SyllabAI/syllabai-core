package com.syllabai.assessment;

import com.syllabai.assessment.dto.MarkSchemeRevealView;
import com.syllabai.assessment.dto.QuestionTopicTaxonomyView;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing question endpoints (Master Spec §22). MCQs serve from the flat
 * question projection; STRUCTURED questions serve the current version with parts.
 * The servability boundary itself lives in {@link ServableQuestionService} (one
 * owner, reused by other read models since T-033).
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final ServableQuestionService servableQuestions;
    private final MarkSchemeRevealService markSchemeReveal;
    private final com.syllabai.knowledge.KnowledgeGraphService knowledgeGraph;

    public QuestionController(ServableQuestionService servableQuestions,
                              MarkSchemeRevealService markSchemeReveal,
                              com.syllabai.knowledge.KnowledgeGraphService knowledgeGraph) {
        this.servableQuestions = servableQuestions;
        this.markSchemeReveal = markSchemeReveal;
        this.knowledgeGraph = knowledgeGraph;
    }

    @GetMapping
    public List<StudentQuestionView> list(@RequestParam(required = false) UUID topicNodeId,
                                          @RequestParam(required = false) UUID rootId) {
        if (topicNodeId != null) {
            return servableQuestions.activeByTopic(topicNodeId);
        }
        if (rootId != null) {
            // subject-scoped practice (pilot-readiness session-56): only the
            // questions mapped inside the subject's PART_OF subtree serve
            return servableQuestions.activeWithin(knowledgeGraph.subtreeIds(rootId));
        }
        return servableQuestions.allActive();
    }

    /**
     * The servable-question taxonomy (session-112): sections → topics with
     * counts, the shape the exam-questions browser sidebar and the practice
     * topic picker render. Counts follow the same servability + topic-reachability
     * rule as {@link #list} — a topic's count is the length of its list.
     * {@code rootId} scopes to a subject's subtree (unknown roots 404 like list).
     */
    @GetMapping("/topics")
    public QuestionTopicTaxonomyView topics(@RequestParam(required = false) UUID rootId) {
        return servableQuestions.taxonomy(rootId);
    }

    @GetMapping("/{id}")
    public StudentQuestionView get(@PathVariable UUID id) {
        return servableQuestions.findById(id)
                .orElseThrow(() -> new NotFoundException("question", id));
    }

    /**
     * Save-My-Exams-style mark-scheme reveal, governed by
     * {@link MarkSchemeRevealService}'s policy: 200 with the scheme when the
     * policy serves it, 204 when it withholds (pending teacher validation /
     * rejected / flagged) so the UI can say so honestly, 404 when the question
     * itself is not servable.
     */
    @GetMapping("/{id}/mark-scheme")
    public ResponseEntity<MarkSchemeRevealView> markScheme(@PathVariable UUID id) {
        return markSchemeReveal.reveal(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
