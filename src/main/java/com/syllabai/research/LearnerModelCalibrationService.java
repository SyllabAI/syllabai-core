package com.syllabai.research;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S2/ADR-033 — the calibration instrument. Reads the {@code BKT_UPDATED}
 * telemetry stream (append-only since V5) and aggregates predicted-vs-observed:
 * every BKT update recorded the PRE-attempt mastery ({@code priorMastery} — the
 * model's probability that the learner knows the skill, made BEFORE the attempt
 * was scored) next to the attempt's observed outcome. That is exactly a
 * probabilistic-forecast record, so the standard instruments apply: Brier score
 * and expected calibration error over ten equal-width mastery bins.
 *
 * <p>Read-only over telemetry; no new writes, no schema change. The retuning of
 * constants itself (Paper B's "monthly parameter recalibration") stays a
 * data-gated decision — this service is what makes that decision measurable at
 * all. Note the honesty caveat on {@link LearnerModelCalibrationReport#samples()}:
 * one marked attempt updates every node it honestly tests, so samples are
 * node-outcomes, not independent learners.</p>
 */
@Service
public class LearnerModelCalibrationService {

    /** ten equal-width bins across [0,1] — mastery bands 0.0–0.1 … 0.9–1.0 */
    private static final int BIN_COUNT = 10;

    private final TelemetryEventRepository telemetry;

    public LearnerModelCalibrationService(TelemetryEventRepository telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * @param nodeId optional filter — restrict the aggregate to one KG node
     *               (topic or spec point); null aggregates the whole model
     */
    @Transactional(readOnly = true)
    public LearnerModelCalibrationReport report(UUID nodeId) {
        List<TelemetryEvent> rows = telemetry.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED);

        List<double[]> samples = new ArrayList<>();   // {prior, outcome}
        for (TelemetryEvent row : rows) {
            var payload = row.payload();
            if (nodeId != null && !nodeId.toString().equals(String.valueOf(payload.get("nodeId")))) {
                continue;
            }
            // jsonb round-trip: numbers may deserialize as Integer/Double — read
            // them as Number, not Double. Malformed rows are SKIPPED, not fatal:
            // the instrument must stay honest about what it could read.
            if (!(payload.get("priorMastery") instanceof Number priorRaw)
                    || !(payload.get("correctness") instanceof Boolean correct)) {
                continue;
            }
            double prior = clamp01(priorRaw.doubleValue());
            samples.add(new double[]{prior, correct ? 1.0 : 0.0});
        }

        if (samples.isEmpty()) {
            return new LearnerModelCalibrationReport(0, null, null, List.of());
        }

        double[][] bins = new double[BIN_COUNT][4];   // per bin: {count, predictedSum, outcomeSum, brierSum}
        for (double[] s : samples) {
            double prior = s[0];
            double outcome = s[1];
            int bin = binIndex(prior);
            double diff = prior - outcome;
            bins[bin][0] += 1;
            bins[bin][1] += prior;
            bins[bin][2] += outcome;
            bins[bin][3] += diff * diff;
        }

        List<LearnerModelCalibrationReport.Bin> binViews = new ArrayList<>();
        double ece = 0.0;
        for (int b = 0; b < BIN_COUNT; b++) {
            int count = (int) bins[b][0];
            if (count == 0) {
                continue;
            }
            double lower = b / (double) BIN_COUNT;
            double upper = (b + 1) / (double) BIN_COUNT;
            double meanPredicted = bins[b][1] / count;
            double observedAccuracy = bins[b][2] / count;
            binViews.add(new LearnerModelCalibrationReport.Bin(
                    lower, upper, count, meanPredicted, observedAccuracy, bins[b][3] / count,
                    observedAccuracy - meanPredicted));
            ece += (count / (double) samples.size()) * Math.abs(observedAccuracy - meanPredicted);
        }

        double brierSum = 0.0;
        for (double[] s : samples) {
            double diff = s[0] - s[1];
            brierSum += diff * diff;
        }

        return new LearnerModelCalibrationReport(
                samples.size(), brierSum / samples.size(), ece, List.copyOf(binViews));
    }

    private static int binIndex(double prior) {
        int idx = (int) Math.floor(prior * BIN_COUNT);
        return Math.max(0, Math.min(BIN_COUNT - 1, idx));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
