package com.syllabai.smartmark;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing Smart Mark views (the student-triggered counterpart of the
 * teacher marking queue's {@code TeacherViews.SmartMarkView}). Deliberately
 * narrow: per-point decisions carry the point reference, text, marks, award
 * decision, the student's own quoted evidence and the marker rationale — the
 * same content the reveal boundary would show, now bound to the learner's
 * answer. {@code acceptanceCriteria} and extraction metadata never leave the
 * backend (Master Spec §15/§20/§22 — a learner surface is not a marking
 * surface; the explain/improvement generations are derived views, not raw
 * scheme dumps).
 */
public final class StudentSmartMarkViews {

    private StudentSmartMarkViews() {
    }

    /** result of smart-marking every markable part of the caller's attempt */
    public record AttemptSmartMarkView(
            UUID attemptId,
            UUID questionId,
            String schemeValidationState,
            int marksPossible,
            List<PartSmartMarkView> parts) {
    }

    /** one part's marking result, projected for the student */
    public record PartSmartMarkView(
            UUID partId,
            String label,
            int marksAwarded,
            int marksPossible,
            String markingState,
            /** κ release gate state at marking time — honest, not silent */
            boolean authoritative,
            Double confidence,
            String modelId,
            boolean validationPassed,
            String failureReason,
            List<PointDecisionView> breakdown) {
    }

    /**
     * one mark point's award decision joined with its scheme text. The point
     * text never leaves the backend in full — the no-reveal Smart Mark flow
     * shows only a compact {@code pointLabel} (first meaningful line of the
     * scheme text), so the model answer and teaching notes cannot leak through
     * the breakdown the way the full text would (operator scenario 2026-09-21:
     * a 3-mark compound point rendered its whole scheme blob as "feedback").
     * Partial credit: {@code marksAwarded} may sit between 0 and {@code marks}.
     */
    public record PointDecisionView(
            String ref,
            String pointLabel,
            int marks,
            int marksAwarded,
            boolean awarded,
            /** shortest verbatim quote from the learner answer that justified the decision */
            String evidence,
            String rationale) {
    }

    /** "Explain my feedback" — grounded explanation of the recorded decisions */
    public record FeedbackExplanationView(
            UUID partId,
            String explanation,
            String modelId,
            Instant generatedAt) {
    }

    /** "Improve my answer" — coaching toward the not-awarded mark points */
    public record ImprovementPlanView(
            UUID partId,
            String plan,
            String modelId,
            Instant generatedAt) {
    }
}
