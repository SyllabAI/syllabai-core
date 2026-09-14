package com.syllabai.learner;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.learner.dto.SmartLessonView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smart Lesson MVP (productization sprint §2): the learner picks a topic, the
 * engine returns ONE explainable next action computed deterministically from
 * the existing learner state. Route security: authenticated (learner surface).
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class SmartLessonController {

    private final SmartLessonService smartLesson;

    public SmartLessonController(SmartLessonService smartLesson) {
        this.smartLesson = smartLesson;
    }

    /**
     * The next action for a topic. {@code rootId} scopes the subject (hard
     * isolation — a topic outside the subtree is a 404); {@code topicNodeId}
     * is the selected curriculum node. Re-query after acting: new evidence
     * changes the decision (closed loop).
     */
    @GetMapping("/smart-lesson")
    public SmartLessonView smartLesson(@CurrentUserId UUID learnerId,
                                       @RequestParam UUID rootId,
                                       @RequestParam UUID topicNodeId) {
        return smartLesson.lessonFor(learnerId, rootId, topicNodeId);
    }
}
