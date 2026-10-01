package com.syllabai.research;

import java.util.List;
import java.util.UUID;

/**
 * S2/ADR-033 — the calibration instrument Master Spec §BKT ("monthly parameter
 * recalibration") and §5.5 (research layer charter: "calibration") promise but
 * the codebase never had. Computed READ-ONLY from the {@code BKT_UPDATED}
 * telemetry stream, which has recorded every BKT update's pre-attempt prediction
 * ({@code priorMastery}) next to the observed outcome ({@code correctness})
 * since V5.
 *
 * @param samples node-outcome pairs aggregated (one marked attempt may update
 *                several nodes — topic + mapped spec points — so samples are
 *                node-outcomes, not independent learners; the numbers are
 *                descriptive, not i.i.d. statistics)
 * @param brier   mean (predicted − outcome)² over all samples; null when empty
 * @param ece     expected calibration error — Σ (n_b/N)·|observed_b − predicted_b|
 *                over the ten equal-width mastery bins; null when empty
 * @param bins    the NON-EMPTY bins only, ordered by mastery band
 */
public record LearnerModelCalibrationReport(
        int samples,
        Double brier,
        Double ece,
        List<Bin> bins) {

    /**
     * One mastery band's predicted-vs-observed record.
     *
     * @param calibrationError observed − predicted (positive = model
     *                         UNDER-predicts mastery at this band)
     */
    public record Bin(
            double lower,
            double upper,
            int samples,
            Double meanPredicted,
            Double observedAccuracy,
            Double meanBrier,
            Double calibrationError) {
    }
}
