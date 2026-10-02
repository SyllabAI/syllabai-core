package com.syllabai.learner;

import com.syllabai.learner.flashcard.FlashcardReviewParams;
import java.time.Duration;
import java.util.List;

/**
 * Learner-model configuration (prefix {@code syllabai.learner}). Values default to
 * the Paper B research-design numbers (Master Spec §11); overrides are versioned
 * through the model_versions registry.
 *
 * <p>Binding note: this is a {@code @ConfigurationProperties} record —
 * constructor binding requires the single canonical constructor, so NO
 * compatibility overload exists (a second constructor breaks context boot
 * with "No default constructor found"). Callers pass {@code null} as the
 * fifth argument for the shipped flashcard ladder; the compact constructor
 * normalizes it.</p>
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "syllabai.learner")
public record LearnerProperties(
        Bkt bkt,
        Decay decay,
        Bdt bdt,
        DecayJob decayJob,
        FlashcardReview flashcardReview) {

    /**
     * BKT emission parameters (Master Spec §11 / Paper B research design).
     *
     * <p>Research-design parameters, versioned through the {@code model_versions}
     * registry and configurable via {@code syllabai.learner.bkt.*} — never hard-coded
     * in domain logic. S2/ADR-033: guess is priced per question format — the paper's
     * {@code guess} of 0.25 is exactly the FOUR-OPTION MCQ base, not a universal
     * constant.</p>
     *
     * @param l0               initial knowledge probability P(L₀)
     * @param slip             probability of a wrong answer despite knowing (global —
     *                         mistakes happen in every format; ADR-033 declined
     *                         format-aware slip at Cycle-1 volume)
     * @param guess            default guess prior — used for MCQ_SINGLE when the option
     *                         count is unusable (S2 challenge C3 guard) and for
     *                         null/blank/unknown formats, so legacy untyped evidence
     *                         keeps its exact historical behaviour
     * @param learnRate        probability of transitioning unlearned→learned per opportunity
     * @param shortAnswerGuess guess prior for SHORT_ANSWER (provisional 0.05 — S2
     *                         challenge C5: revisit at the first calibration report)
     * @param structuredGuess  guess prior for STRUCTURED (0.01: a multi-part worked
     *                         answer earning FULL marks by blind luck is near-impossible;
     *                         the 0.25 paper constant under-credited structured learners
     *                         2.57× per correct answer)
     */
    public record Bkt(double l0, double slip, double guess, double learnRate,
                      double shortAnswerGuess, double structuredGuess) {
        public Bkt {
            if (l0 <= 0) l0 = 0.1;
            if (slip <= 0) slip = 0.1;
            if (guess <= 0) guess = 0.25;
            if (learnRate <= 0) learnRate = 0.1;
            if (shortAnswerGuess <= 0) shortAnswerGuess = 0.05;
            if (structuredGuess <= 0) structuredGuess = 0.01;
        }

        public com.syllabai.learner.bkt.BktParams toParams() {
            return toParams(null, 0);
        }

        /**
         * Format-aware emission resolution (S2/ADR-033): guess is priced per question
         * format; slip, l₀ and T stay global.
         *
         * <p>Resolution: {@code MCQ_SINGLE} with a usable option count →
         * {@code 1/optionCount} (the paper's 0.25 is exactly N=4, so four-option MCQs
         * are bit-identical to the legacy path); {@code SHORT_ANSWER} →
         * {@code shortAnswerGuess}; {@code STRUCTURED} → {@code structuredGuess};
         * null/blank/unknown format → the paper default 0.25 — a strict refinement,
         * never a behaviour change for untyped events.</p>
         *
         * <p>Domain guards (S2 challenge C3): an MCQ count below 2 resolves to the
         * paper default — {@code 1/1 = 1.0} would make wrong answers RAISE mastery
         * ({@code BktEngine}'s wrong-path evidence divides by {@code (1−guess)}), and
         * {@code 1/0} would 500 the submission inside {@code BktParams}' probability
         * validation. Malformed option counts are a data-quality tail (teacher-authored
         * and ingested items), not a configuration error, so the guard degrades to the
         * legacy prior instead of failing the attempt. The resolved guess is also
         * clamped strictly below {@code 1 − slip}: at guess ≥ 1 − slip a wrong answer
         * stops being evidence of anything (posterior = prior), and above it wrong
         * answers RAISE mastery — a misconfigured knob must degrade, never invert the
         * update.</p>
         *
         * @param questionType the {@code Question.Type} name carried on the evidence
         *                     event (nullable — untyped/legacy events resolve to the
         *                     paper default)
         * @param optionCount  the live option count for MCQ events, 0 otherwise
         */
        public com.syllabai.learner.bkt.BktParams toParams(String questionType, int optionCount) {
            double resolved;
            if ("MCQ_SINGLE".equals(questionType)) {
                resolved = optionCount >= 2 ? 1.0 / optionCount : guess;
            } else if ("SHORT_ANSWER".equals(questionType)) {
                resolved = shortAnswerGuess;
            } else if ("STRUCTURED".equals(questionType)) {
                resolved = structuredGuess;
            } else {
                resolved = guess;
            }
            // clamp strictly below 1 − slip (see javadoc): degrade, never invert
            resolved = Math.min(resolved, 1.0 - slip - 1e-9);
            return new com.syllabai.learner.bkt.BktParams(l0, slip, resolved, learnRate);
        }
    }

    public record Decay(int tauLowDays, int tauMidDays, int tauHighDays,
                        double lowBandCeiling, double highBandFloor,
                        double floor, double reviewBelow) {
        public Decay {
            if (tauLowDays <= 0) tauLowDays = 30;
            if (tauMidDays <= 0) tauMidDays = 90;
            if (tauHighDays <= 0) tauHighDays = 365;
            if (lowBandCeiling <= 0) lowBandCeiling = 0.45;
            if (highBandFloor <= 0) highBandFloor = 0.8;
            if (floor <= 0) floor = 0.1;
            if (reviewBelow <= 0) reviewBelow = 0.6;
        }

        public com.syllabai.learner.decay.DecayParams toParams() {
            return new com.syllabai.learner.decay.DecayParams(
                    tauLowDays, tauMidDays, tauHighDays,
                    lowBandCeiling, highBandFloor, floor, reviewBelow);
        }
    }

    public record Bdt(double prior, double selectIfHeld, double selectIfNotHeld,
                      double activeThreshold, int stalenessTauDays) {
        public Bdt {
            if (prior <= 0) prior = 0.3;
            if (selectIfHeld <= 0) selectIfHeld = 0.7;
            if (selectIfNotHeld <= 0) selectIfNotHeld = 0.1;
            if (activeThreshold <= 0) activeThreshold = 0.5;
            // MED-2/ADR-032: how fast stale BDT evidence relaxes toward the prior.
            // 180d = a stale diagnosis loses prescribing force over ~a term-to-year
            // horizon — gentler than the fastest mastery band (30d) because beliefs
            // outlive facts; recalibration (S2) may retune.
            if (stalenessTauDays <= 0) stalenessTauDays = 180;
        }

        public com.syllabai.learner.bdt.BdtParams toParams() {
            return new com.syllabai.learner.bdt.BdtParams(prior, selectIfHeld, selectIfNotHeld);
        }
    }

    /**
     * @param checkCron      how often the run-if-missed checker ticks (session-114;
     *                       was a single 03:00 cron that slept through on the free
     *                       tier). Every tick runs the batch iff the current
     *                       window's ledger row is absent.
     * @param windowHourUtc  the UTC hour the nightly window opens (default 3 —
     *                       the historical fire time, kept as the window anchor)
     */
    public record DecayJob(boolean enabled, String checkCron, int windowHourUtc,
                           Duration idleGracePeriod) {
        public DecayJob {
            if (checkCron == null || checkCron.isBlank()) checkCron = "0 */15 * * * *";
            if (windowHourUtc < 0 || windowHourUtc > 23) windowHourUtc = 3;
            if (idleGracePeriod == null) idleGracePeriod = Duration.ofDays(2);
        }
    }

    /**
     * Flashcard review ladder (T-C53 — the V47 trail's reserved scheduler):
     * expanding Ebbinghaus intervals (days) by consecutive-"know" streak,
     * mirrored bit-for-bit from the hub's shipped lib/flashcard-review.ts
     * (tranche 4.8). Registry-seeded as {@code learner.flashcard-review} (V59);
     * the schedule derived from it is computed at read and never persisted
     * (ADR-031) and drives TIMING only — never mastery (the honesty pin).
     *
     * @param intervalDays the ladder; the last entry is the maintenance cap
     *                     (any streak ≥ ladder length maps to it). Lenient:
     *                     null/empty/malformed falls back to the shipped
     *                     default, matching the Bkt/Decay/Bdt convention.
     */
    public record FlashcardReview(List<Integer> intervalDays) {
        public FlashcardReview {
            if (intervalDays == null || intervalDays.isEmpty()
                    || intervalDays.stream().anyMatch(d -> d == null || d <= 0)) {
                intervalDays = FlashcardReviewParams.DEFAULT_INTERVAL_DAYS;
            }
        }

        public FlashcardReviewParams toParams() {
            return new FlashcardReviewParams(intervalDays);
        }
    }

    public LearnerProperties {
        if (bkt == null) bkt = new Bkt(0.1, 0.1, 0.25, 0.1, 0.05, 0.01);
        if (decay == null) decay = new Decay(30, 90, 365, 0.45, 0.8, 0.1, 0.6);
        if (bdt == null) bdt = new Bdt(0.3, 0.7, 0.1, 0.5, 180);
        if (decayJob == null) decayJob = new DecayJob(false, "0 */15 * * * *", 3, Duration.ofDays(2));
        if (flashcardReview == null) flashcardReview = new FlashcardReview(null);
    }
}
