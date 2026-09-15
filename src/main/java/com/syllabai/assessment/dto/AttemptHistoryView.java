package com.syllabai.assessment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only learning history for the requesting learner (the Review Hub's
 * minimal Cycle-1 slice — charter §14). This is a <strong>view</strong> over
 * the immutable {@code attempts}/{@code answers} evidence rows: no state is
 * derived here, no tracking system is introduced, and nothing is recomputed.
 *
 * <p>Honesty rules (mirroring the evidence invariants, Master Spec §12):</p>
 * <ul>
 *   <li>{@code correct} is null for structured attempts while any part is
 *       pending; once every part is authoritatively marked it carries the
 *       settled attempt row's classification (the same conservative full-marks
 *       rule the evidence event used) — the UI must not render a guess, and
 *       the view must not invent a different one.</li>
 *   <li>{@code marksAwarded} is null while marking is pending.</li>
 *   <li>MCQ answers carry the chosen option and the correct option label —
 *       the same facts the immediate AttemptResultView revealed at submit
 *       time, so showing them later leaks nothing new.</li>
 *   <li>Structured part answers are deliberately NOT echoed back in this
 *       view (Cycle-1 surface shows outcomes, not stored answer text; the
 *       teacher marking queue remains the only surface for full text).</li>
 * </ul>
 */
public record AttemptHistoryView(
        UUID learnerId,
        int total,
        int returned,
        List<Item> attempts) {

    /** One past attempt with everything the student needs to review it. */
    public record Item(
            UUID attemptId,
            UUID questionId,
            String questionType,
            String externalRef,
            String commandWord,
            String stemExcerpt,
            int marksTotal,
            UUID topicNodeId,
            String topicCode,
            String topicTitle,
            /** null for structured attempts pending authoritative marking */
            Boolean correct,
            /** null while marking is pending */
            Integer marksAwarded,
            String markingState,
            boolean evidenceEmitted,
            /** MCQ only: the option label the learner chose */
            String chosenOptionLabel,
            /** MCQ only: the correct option label (already revealed at submit) */
            String correctOptionLabel,
            /** MCQ only: misconception node the chosen distractor expresses */
            List<UUID> implicatedMisconceptionIds,
            boolean selfDoubtFlag,
            boolean timedCondition,
            Integer confidenceLevel,
            long responseTimeMs,
            Instant attemptedAt,
            /** structured attempts only */
            List<PartItem> parts) {
    }

    /** One written part of a structured attempt: labels and mark outcome only. */
    public record PartItem(
            UUID partId,
            String label,
            int marksPossible,
            Integer marksAwarded,
            String markingState) {
    }
}
