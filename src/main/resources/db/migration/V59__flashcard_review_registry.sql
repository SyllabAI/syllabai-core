-- V59: flashcard review ladder — model_versions registry seed (T-C53).
--
-- DATA-ONLY migration: NO schema change. The flashcard_ratings table (V47)
-- is untouched; the schedule it feeds is derived at READ time and persisted
-- nowhere (ADR-031 doctrine — decay/scheduling is computed, never stored;
-- the review_schedules table stays the marked-attempt spec-point queue).
--
-- WHY A REGISTRY ROW: the ladder is a learner-model parameter family in the
-- exact sense of the V6 seeds (learner.bkt / learner.bdt / learner.decay) —
-- research-design numbers that configurable overrides replace, versioned
-- per §19 reproducibility. The feed's defaults (syllabai.learner.flashcard-
-- review.interval-days, application.yml) and this row carry the SAME v1
-- numbers, mirrored bit-for-bit from the hub's shipped Ebbinghaus ladder
-- (syllabai-hub lib/flashcard-review.ts, tranche 4.8): "still learning" is
-- due immediately; a consecutive-"know" streak of n resurfaces after
-- intervalDays[min(n-1, size-1)] days — 1·2·4·8·16, capped at a 32-day
-- maintenance cycle. Hub and core therefore derive the SAME due date from
-- the same trail; a future recalibration retunes one registry entry, not
-- arithmetic buried in either codebase.
--
-- EVIDENCE-CLASS GUARD (restated for the schema record): this ladder drives
-- review TIMING only. Ratings never produce mastery, never touch
-- SkillState/misconception state, never write review_schedules — pinned by
-- FlashcardRatingFlowIT (the honesty pin) and, from T-C53,
-- FlashcardReviewScheduleFlowIT.

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('60000000-0000-0000-0000-000000000004', 'learner.flashcard-review', 'v1-hub-parity',
     '{"intervalDays": [1, 2, 4, 8, 16, 32]}'::jsonb,
     'Hub tranche 4.8 shipped ladder (lib/flashcard-review.ts), mirrored bit-for-bit; operator trace 1a0fb9ceb36d36d1',
     'Defaults; expanding Ebbinghaus by know-streak, 32d maintenance cap. Schedule computed at read (ADR-031), timing only (honesty pin)', now());
