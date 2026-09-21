package com.syllabai.learner;

import java.time.Duration;

/**
 * Learner-model configuration (prefix {@code syllabai.learner}). Values default to
 * the Paper B research-design numbers (Master Spec §11); overrides are versioned
 * through the model_versions registry.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "syllabai.learner")
public record LearnerProperties(
        Bkt bkt,
        Decay decay,
        Bdt bdt,
        DecayJob decayJob) {

    public record Bkt(double l0, double slip, double guess, double learnRate) {
        public Bkt {
            if (l0 <= 0) l0 = 0.1;
            if (slip <= 0) slip = 0.1;
            if (guess <= 0) guess = 0.25;
            if (learnRate <= 0) learnRate = 0.1;
        }

        public com.syllabai.learner.bkt.BktParams toParams() {
            return new com.syllabai.learner.bkt.BktParams(l0, slip, guess, learnRate);
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
                      double activeThreshold) {
        public Bdt {
            if (prior <= 0) prior = 0.3;
            if (selectIfHeld <= 0) selectIfHeld = 0.7;
            if (selectIfNotHeld <= 0) selectIfNotHeld = 0.1;
            if (activeThreshold <= 0) activeThreshold = 0.5;
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

    public LearnerProperties {
        if (bkt == null) bkt = new Bkt(0.1, 0.1, 0.25, 0.1);
        if (decay == null) decay = new Decay(30, 90, 365, 0.45, 0.8, 0.1, 0.6);
        if (bdt == null) bdt = new Bdt(0.3, 0.7, 0.1, 0.5);
        if (decayJob == null) decayJob = new DecayJob(false, "0 */15 * * * *", 3, Duration.ofDays(2));
    }
}
