package com.syllabai.assessment.dto;

import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.QuestionPart;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing mark-scheme reveal (the Save-My-Exams-style "mark scheme"
 * panel). Deliberately narrow: point text + marks only, grouped under the
 * question's parts. {@code acceptanceCriteria} (the teacher-authored
 * deterministic matching evidence behind Smart Mark) and extraction metadata
 * never leave the backend — a learner surface is not a marking surface
 * (Master Spec §15/§20/§22).
 *
 * <p>{@code validationState} is carried honestly so the UI can label
 * AI-extracted (SUGGESTED) schemes as pending teacher validation when the
 * operator's reveal policy allows them to serve at all.</p>
 */
public record MarkSchemeRevealView(
        UUID questionId, String questionExternalRef, UUID schemeId,
        String validationState, int schemeMarks, int questionMarks,
        List<PartScheme> parts, List<PointView> generalPoints) {

    public record PointView(String ref, String text, int marks) {

        public static PointView from(MarkPoint p) {
            return new PointView(p.ref(), p.text(), p.marks());
        }
    }

    public record PartScheme(UUID partId, String label, String prompt, int marks,
                             List<PointView> points) {

        public static PartScheme from(QuestionPart part, List<PointView> points) {
            return new PartScheme(part.id(), part.label(), part.prompt(), part.marks(), points);
        }
    }
}
