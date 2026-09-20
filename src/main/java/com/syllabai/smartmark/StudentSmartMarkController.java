package com.syllabai.smartmark;

import com.syllabai.identity.CurrentUserId;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student-facing Smart Mark routes (Master Spec §15 — the learner half of
 * F-047): after a structured submission the learner opens Smart Mark on their
 * own attempt, and then the two feedback actions on a marked part.
 *
 * <pre>
 *   POST /api/v1/learners/me/attempts/{attemptId}/smart-mark
 *        → AI-marks every markable part through the teacher-lane pipeline
 *          (κ-gated authority, honest {@code authoritative} flag)
 *   POST /api/v1/learners/me/attempts/{attemptId}/parts/{partId}/feedback-explanation
 *        → "Explain my feedback" — grounded walk-through of the decisions
 *   POST /api/v1/learners/me/attempts/{attemptId}/parts/{partId}/improvement-plan
 *        → "Improve my answer" — coaching toward the missing mark points
 * </pre>
 *
 * <p>Route security: authenticated learner surface under /api/v1/learners/me
 * (the SecurityConfig default rule, same as the CLA and self-mark surfaces).
 * The service re-verifies ownership against the authenticated caller, so
 * another learner's attempt id is a plain 404 — no existence leak.</p>
 *
 * <p>Button-driven, not a chat box: the feedback actions take no request body
 * — the grounding (question, answer, decisions) is resolved server-side from
 * opaque ids, exactly like the CLA contract's context resolution.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/attempts")
public class StudentSmartMarkController {

    private final StudentSmartMarkService studentSmartMark;

    public StudentSmartMarkController(StudentSmartMarkService studentSmartMark) {
        this.studentSmartMark = studentSmartMark;
    }

    @PostMapping("/{attemptId}/smart-mark")
    public StudentSmartMarkViews.AttemptSmartMarkView smartMark(
            @CurrentUserId UUID learnerId,
            @PathVariable UUID attemptId) {
        return studentSmartMark.smartMarkAttempt(learnerId, attemptId);
    }

    @PostMapping("/{attemptId}/parts/{partId}/feedback-explanation")
    public StudentSmartMarkViews.FeedbackExplanationView explainFeedback(
            @CurrentUserId UUID learnerId,
            @PathVariable UUID attemptId,
            @PathVariable UUID partId) {
        return studentSmartMark.explainFeedback(learnerId, attemptId, partId);
    }

    @PostMapping("/{attemptId}/parts/{partId}/improvement-plan")
    public StudentSmartMarkViews.ImprovementPlanView improvementPlan(
            @CurrentUserId UUID learnerId,
            @PathVariable UUID attemptId,
            @PathVariable UUID partId) {
        return studentSmartMark.improvementPlan(learnerId, attemptId, partId);
    }
}
