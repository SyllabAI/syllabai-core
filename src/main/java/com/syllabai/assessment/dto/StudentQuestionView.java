package com.syllabai.assessment.dto;

import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing question projection — correct answers and misconception tags are
 * stripped (Master Spec §22: DTOs at boundaries, §20: do not leak answers).
 * For STRUCTURED questions the parts of the current version are included; MCQs
 * carry their options as before. specPointCodes (ADR-026) carries the question's
 * curriculum codes (e.g. 4CH1-1.15, PRIMARY first) so the client can join the
 * question to relevant revision notes without another round trip — curriculum
 * metadata only, never answer content.
 */
public record StudentQuestionView(
        UUID id, String externalRef, String type, String stem, int marks,
        int difficulty, int expectedTimeSeconds, String commandWord,
        UUID primaryTopicNodeId, UUID examPaperId, List<OptionView> options,
        List<PartView> parts, List<String> specPointCodes) {

    public record OptionView(UUID id, String label, String text) {
    }

    /** one lettered sub-question; no answer content is exposed */
    public record PartView(UUID id, String label, String prompt, String commandWord,
                           int marks) {
    }

    public static StudentQuestionView from(Question q) {
        return new StudentQuestionView(
                q.id(), q.externalRef(), q.type().name(), q.stem(), q.marks(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                q.primaryTopicNodeId(), q.examPaperId(),
                q.options().stream()
                        .map(o -> new OptionView(o.id(), o.label(), o.text()))
                        .toList(),
                List.of(), List.of());
    }

    /** copy with the curriculum codes attached (PRIMARY first, then SECONDARY) */
    public StudentQuestionView withSpecPointCodes(List<String> codes) {
        return new StudentQuestionView(id, externalRef, type, stem, marks, difficulty,
                expectedTimeSeconds, commandWord, primaryTopicNodeId, examPaperId,
                options, parts, codes == null ? List.of() : codes);
    }

    public static StudentQuestionView structured(Question q, QuestionVersion version) {
        List<PartView> parts = version.parts().stream()
                .map(p -> new PartView(p.id(), p.label(), p.prompt(), p.commandWord(), p.marks()))
                .toList();
        return new StudentQuestionView(
                q.id(), q.externalRef(), q.type().name(),
                version.stem() == null ? q.stem() : version.stem(),
                version.marks() > 0 ? version.marks() : q.marks(),
                version.difficulty(), version.expectedTimeSeconds(), version.commandWord(),
                q.primaryTopicNodeId(), q.examPaperId(), List.of(), parts, List.of());
    }

    public static StudentQuestionView withParts(Question q, List<QuestionPart> parts) {
        return new StudentQuestionView(
                q.id(), q.externalRef(), q.type().name(), q.stem(), q.marks(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                q.primaryTopicNodeId(), q.examPaperId(),
                q.options().stream()
                        .map(o -> new OptionView(o.id(), o.label(), o.text()))
                        .toList(),
                parts.stream()
                        .map(p -> new PartView(p.id(), p.label(), p.prompt(), p.commandWord(), p.marks()))
                        .toList(),
                List.of());
    }
}
