package com.syllabai.assessment.dto;

import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing question projection — correct answers and misconception tags are
 * stripped (Master Spec §22: DTOs at boundaries, §20: do not leak answers).
 */
public record StudentQuestionView(
        UUID id, String externalRef, String type, String stem, int marks,
        int difficulty, int expectedTimeSeconds, String commandWord,
        UUID primaryTopicNodeId, List<OptionView> options) {

    public record OptionView(UUID id, String label, String text) {
    }

    public static StudentQuestionView from(Question q) {
        return new StudentQuestionView(
                q.id(), q.externalRef(), q.type().name(), q.stem(), q.marks(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                q.primaryTopicNodeId(),
                q.options().stream()
                        .map(o -> new OptionView(o.id(), o.label(), o.text()))
                        .toList());
    }
}
