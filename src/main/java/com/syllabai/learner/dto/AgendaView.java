package com.syllabai.learner.dto;

import com.syllabai.assignment.dto.AssignmentViews.LearnerAssignmentView;
import com.syllabai.recommendation.dto.NextBestActionsView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner agenda read model (T-C76, Master Spec §22 route row
 * {@code GET /api/v1/learners/me/agenda}): the single call that answers
 * "what is on my plate?" — due spaced reviews, the work the teacher set
 * (with my current hand-in beside each) and, when a subject root is
 * supplied, the evidence-backed next best actions.
 *
 * <p>Honesty contract (the T-028/T-034/CourseStats pattern — the server
 * composes, the client never joins): a pure read over the SAME write paths
 * the three composed surfaces already serve —
 * {@code LearnerStateController} pending reviews (V18), the V49/V51
 * assignment visibility filter with the learner's own append-only hand-in
 * trail, and the deterministic NBA engine (ADR-017) when {@code rootId} is
 * present. Zero derivation beyond ordering, zero writes, zero new
 * persistence, no new actor: no notification channel, no plan persistence,
 * no state flips (the executive-layer ADR-035 proposal is a separate,
 * operator-gated card — this endpoint pre-derives nothing it cannot cite).
 * Reactions to the agenda stay client-side; nothing here touches SkillState,
 * BKT, misconception or review state.</p>
 *
 * <ul>
 *   <li>{@code dueReviews} — PENDING spaced-review rows, due-soonest first;
 *       node titles resolved (the P1 batched-titles rule, no raw UUIDs).</li>
 *   <li>{@code assignments} — the same rows {@code GET .../assignments}
 *       serves for this learner (V51: null class target = the whole enabled
 *       cohort; a class target = its members only), with the learner's
 *       latest hand-in beside each (null until they submit); ordered
 *       due-soonest-first, undated last — an agenda, not a newest-first
 *       feed. Same 50-row bound as the teacher/learner lists.</li>
 *   <li>{@code actions} — the full {@link NextBestActionsView} for the
 *       requested subject root (advice, not facts — a failure of the NBA
 *       call surfaces as a real error, never silently dropped advice), or
 *       {@code null} when no {@code rootId} was supplied: the agenda stays
 *       one call, the caller decides whether it needs recommendations.</li>
 * </ul>
 */
public record AgendaView(
        UUID learnerId,
        Instant asOf,
        List<LearnerStateView.ReviewView> dueReviews,
        List<LearnerAssignmentView> assignments,
        NextBestActionsView actions) {

    public AgendaView {
        dueReviews = List.copyOf(dueReviews);
        assignments = List.copyOf(assignments);
    }
}
