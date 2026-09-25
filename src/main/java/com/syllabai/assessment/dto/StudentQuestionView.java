package com.syllabai.assessment.dto;

import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionOption;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Learner-facing question projection — correct answers and misconception tags are
 * stripped (Master Spec §22: DTOs at boundaries, §20: do not leak answers).
 * For STRUCTURED questions the parts of the current version are included; MCQs
 * carry their options as before. specPointCodes (ADR-026) carries the question's
 * curriculum codes (e.g. 4CH1-1.15, PRIMARY first) so the client can join the
 * question to relevant revision notes without another round trip — curriculum
 * metadata only, never answer content.
 *
 * <p>specPoints (T-C24) is the richer sibling of specPointCodes: the same
 * mappings in the same order, each with its mapping role and the spec point's
 * official applicability (papers / double_award_shared / rule for 4CH1 —
 * verbatim from the pinned store, {@code null} when the point carries none).
 * It exists so paper/unit/tier scoping needs no second round trip;
 * specPointCodes stays derived (codes of specPoints) so existing consumers
 * never drift.</p>
 */
public record StudentQuestionView(
        UUID id, String externalRef, String type, String stem, int marks,
        int difficulty, int expectedTimeSeconds, String commandWord,
        UUID primaryTopicNodeId, UUID examPaperId, List<OptionView> options,
        List<PartView> parts, List<String> specPointCodes, List<SpecPointRef> specPoints) {

    public record OptionView(UUID id, String label, String text) {
    }

    /** one lettered sub-question; no answer content is exposed */
    public record PartView(UUID id, String label, String prompt, String commandWord,
                           int marks) {
    }

    /**
     * one mapped spec point: its curriculum code, the mapping role
     * (PRIMARY/SECONDARY), and the official applicability object verbatim
     * (T-C24 — may be null for unscoped points)
     */
    public record SpecPointRef(String code, String role, Map<String, Object> applicability) {
    }

    public static StudentQuestionView from(Question q) {
        return new StudentQuestionView(
                q.id(), q.externalRef(), q.type().name(), q.stem(), q.marks(),
                q.difficulty(), q.expectedTimeSeconds(), q.commandWord(),
                q.primaryTopicNodeId(), q.examPaperId(),
                q.options().stream()
                        .map(o -> new OptionView(o.id(), o.label(), o.text()))
                        .toList(),
                List.of(), List.of(), List.of());
    }

    /**
     * copy with the curriculum refs attached (PRIMARY first, then SECONDARY);
     * specPointCodes stays derived so the two views can never disagree
     */
    public StudentQuestionView withSpecPoints(List<SpecPointRef> refs) {
        List<SpecPointRef> safe = refs == null ? List.of() : List.copyOf(refs);
        return new StudentQuestionView(id, externalRef, type, stem, marks, difficulty,
                expectedTimeSeconds, commandWord, primaryTopicNodeId, examPaperId,
                options, parts,
                safe.stream().map(SpecPointRef::code).toList(), safe);
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
                q.primaryTopicNodeId(), q.examPaperId(), List.of(), parts, List.of(), List.of());
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
                        .map(p -> new PartView(p.id(), p.label(), p.prompt(), p.commandWord(),
                                p.marks()))
                        .toList(),
                List.of(), List.of());
    }
}
