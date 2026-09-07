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
 * The servability boundary itself lives in {@link ServableQuestionService} (one
 * owner, reused by other read models since T-033).
 */
@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final ServableQuestionService servableQuestions;

    public QuestionController(ServableQuestionService servableQuestions) {
        this.servableQuestions = servableQuestions;
    }

    @GetMapping
    public List<StudentQuestionView> list(@RequestParam(required = false) UUID topicNodeId) {
        return topicNodeId == null
                ? servableQuestions.allActive()
                : servableQuestions.activeByTopic(topicNodeId);
    }

    @GetMapping("/{id}")
    public StudentQuestionView get(@PathVariable UUID id) {
        return servableQuestions.findById(id)
                .orElseThrow(() -> new NotFoundException("question", id));
    }
}
