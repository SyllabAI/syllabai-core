package com.syllabai.assessment.dto;

import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import java.util.List;
import java.util.UUID;

/**
 * Teacher/admin-facing question projection — includes correctness and
 * distractor→misconception tags.
 */
public record TeacherQuestionView(
        UUID id, String externalRef, String type, String stem, int marks,
        int difficulty, int expectedTimeSeconds, String commandWord,
        UUID primaryTopicNodeId, boolean active, String provenance,
        List<OptionView> options) {

    public record OptionView(UUID id, String label, String text, boolean correct,
                             UUID misconceptionNodeId) {
    }

    public static TeacherQuestionView from(Question q) {
        return new TeacherQuestionView(
                q.id(), q.externalRef(), q.type().name(), q.stem(), q.marks(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                q.primaryTopicNodeId(), q.active(), q.provenance().name(),
                q.options().stream()
                        .map(o -> new OptionView(o.id(), o.label(), o.text(),
                                o.correct(), o.misconceptionNodeId()))
                        .toList());
    }
}
