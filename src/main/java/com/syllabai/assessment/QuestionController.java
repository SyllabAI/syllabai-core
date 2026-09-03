package com.syllabai.assessment;

import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing question endpoints (Master Spec §22). MCQs serve from the flat
 * question projection; STRUCTURED questions serve the current version with parts.
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    public QuestionController(QuestionRepository questions,
                              QuestionVersionRepository questionVersions) {
        this.questions = questions;
        this.questionVersions = questionVersions;
    }

    @GetMapping
    public List<StudentQuestionView> list(@RequestParam(required = false) UUID topicNodeId) {
        return (topicNodeId == null
                ? questions.findAllActive()
                : questions.findActiveByTopic(topicNodeId))
                .stream().map(this::project).filter(java.util.Objects::nonNull).toList();
    }

    @GetMapping("/{id}")
    public StudentQuestionView get(@PathVariable UUID id) {
        return questions.findWithOptions(id)
                .map(this::project)
                .filter(java.util.Objects::nonNull)
                .orElseThrow(() -> new NotFoundException("question", id));
    }

    /** null when the spec rejects the question (unvalidated content never serves) */
    private StudentQuestionView project(Question question) {
        if (question.type() != Question.Type.STRUCTURED) {
            return servable.isSatisfiedBy(question, null)
                    ? StudentQuestionView.from(question) : null;
        }
        return questionVersions.findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .filter(version -> servable.isSatisfiedBy(question, version))
                .map(version -> StudentQuestionView.structured(question, version))
                .orElse(null);
    }
}
