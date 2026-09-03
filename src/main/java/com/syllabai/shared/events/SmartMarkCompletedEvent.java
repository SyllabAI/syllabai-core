package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A Smart Mark run completed (Master Spec §15; V9 telemetry extension). Emitted for
 * every run — accepted or failed — so the calibration dataset (Paper B) captures the
 * model's full decision history.
 *
 * @param answerId        the marked answer
 * @param attemptId       the owning attempt
 * @param learnerId       the learner
 * @param questionId      the question
 * @param marksAwarded    marks the run awarded (0 when failed validation)
 * @param marksPossible   scheme marks in scope for the part
 * @param validationPassed whether every deterministic validator accepted the candidate
 * @param failureReason   stable failure code when the run failed (null otherwise)
 * @param modelId         model that produced the candidate (§19)
 * @param pipelineVersion pipeline identity
 * @param authoritative   true only when the κ gate has released Smart Mark (marks then
 *                        drive evidence); otherwise the run is provisional
 * @param occurredAt      run completion time
 */
public record SmartMarkCompletedEvent(
        UUID answerId,
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        int marksAwarded,
        int marksPossible,
        boolean validationPassed,
        String failureReason,
        String modelId,
        String pipelineVersion,
        boolean authoritative,
        Instant occurredAt) {
}
