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
 * Learner-facing question endpoints (Master Spec §22).
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionRepository questions;

    public QuestionController(QuestionRepository questions) {
        this.questions = questions;
    }

    @GetMapping
    public List<StudentQuestionView> list(@RequestParam(required = false) UUID topicNodeId) {
        return (topicNodeId == null
                ? questions.findAllActive()
                : questions.findActiveByTopic(topicNodeId))
                .stream().map(StudentQuestionView::from).toList();
    }

    @GetMapping("/{id}")
    public StudentQuestionView get(@PathVariable UUID id) {
        return questions.findWithOptions(id)
                .filter(Question::active)
                .map(StudentQuestionView::from)
                .orElseThrow(() -> new NotFoundException("question", id));
    }
}
