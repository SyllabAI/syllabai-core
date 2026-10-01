package com.syllabai.learner.dto;

import java.util.UUID;

/**
 * The learner's course-wide aggregate statistics (the "course-stats" core
 * contract, ADR-029 tranche 4.10) — one honest answer to "how much of this
 * course have I actually done on my account?", computed server-side over
 * the FULL evidence tables.
 *
 * <p>Why a dedicated contract: every existing learner read model is either
 * windowed (the learner-state view serves the latest 50 rating / vote
 * events; the attempt history returns at most 100 rows) or
 * granularity-limited (skill states sum per-node attempt counts, and the
 * assessment evidence fires at TOPIC granularity, so a client-side sum
 * undercounts). Course-wide coverage counts cannot be derived honestly
 * from any of them — they are computed here, once, from the tables
 * themselves.</p>
 *
 * <p>Honesty rules (the same class as every evidence read model):</p>
 * <ul>
 *   <li>READ-ONLY AGGREGATION — this view derives nothing, writes nothing,
 *       and claims no mastery. Coverage is exposure (what was attempted /
 *       opened / rated), never competence; the mastery record stays
 *       SkillState + marked attempts, untouched by this contract.</li>
 *   <li>SEMANTICS ARE DISTINCT-COVERAGE — {@code attempts} counts every
 *       attempt row (retries included, by design: practice volume is real
 *       activity), while the other three counts are DISTINCT entities
 *       (questions / notes / cards) so they can sit honestly against a
 *       corpus total the way the hub's dashboard rows do.</li>
 *   <li>SCOPE — Cycle 1 has exactly one subject with content (the 4CH1
 *       pilot); all four evidence tables are pilot-scoped by construction.
 *       A second ingested subject will need per-subject grouping; that is
 *       an additive evolution of this view, not a semantic change.</li>
 * </ul>
 *
 * <p>Rate limiting: deliberately NOT in the R8 LLM tier — a read-only
 * count aggregation with zero LLM cost; the auth tier covers abuse (same
 * ruling as V47 ratings and V48 votes).</p>
 */
public record CourseStatsView(
        UUID learnerId,
        /** every attempt row (MCQ + structured, all marking states) */
        int attempts,
        /** distinct questions attempted (a retried question counts once) */
        int distinctQuestions,
        /** distinct revision notes opened (the view marker is idempotent per note) */
        int notesViewed,
        /** distinct flashcards rated, any rating (re-ratings count once) */
        int flashcardsRated) {
}
