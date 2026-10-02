package com.syllabai.research;

import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.bkt.BktParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calibration instrument (S2/ADR-033): aggregates the {@code BKT_UPDATED}
 * telemetry stream into a predicted-vs-observed report — Brier, ECE and ten
 * equal-width bins — so parameter retunes (slip, τ bands, τ_s) can be gated on a
 * before/after comparison instead of intuition. Read-only over telemetry; rows are
 * never written. Malformed rows are skipped and counted honestly.
 *
 * <p><b>What is predicted (S2 challenge C1).</b> The scored forecast is
 * {@code decayedPrior} — the computed, never-persisted pre-attempt estimate the
 * update actually consumed — NOT the stored anchor: binning the anchor would score
 * a quantity no read surface displays and stay permanently blind to decay/τ misfit.
 * Rows predating the C1/C2 contract (no {@code decayedPrior}) are skipped as legacy.</p>
 *
 * <p><b>What is observed, and the emission mapping (S2 challenge C2).</b>
 * {@code correctness} is an <em>emission</em>, not a latent-state probe: even a
 * perfectly latent-calibrated model observes bin accuracy
 * {@code P·(1−slip) + (1−P)·guess}, not {@code P}. The headline {@code meanPredicted}
 * is therefore the per-row EMISSION-mapped prediction
 * {@code latent·(1−slip) + (1−latent)·guess} (slip global, guess resolved per format
 * from the row's {@code questionType}/{@code optionCount} through the same resolver
 * the update path uses), and Brier/ECE are computed on it. The raw latent mean rides
 * beside it ({@code meanLatentPredicted}) for drift forensics. Reading the raw curve
 * against observed accuracy would show a structural error of up to ~±8 points that
 * moves whenever the guess mix changes — a naive retune that "closed" that gap would
 * corrupt the latent state.</p>
 *
 * <p><b>Known limits, stated here on purpose.</b> Samples are node-outcomes, not
 * independent learners (one marked attempt updates every node it honestly tests) —
 * the report is descriptive, not i.i.d. statistics, and carries no confidence
 * intervals. The mapped prediction uses the CURRENT configured slip, so slip
 * retunes change the mapping across the comparison (guess is per-row and immune);
 * segment any such comparison. The gap axis ({@code gapDays}) is on the stream for
 * the τ-band analysis but is not cut here yet — an explicit follow-up, without
 * which τ retunes have no gate. Traffic per bin ({@code count}) is part of the
 * report: an empty bin is no evidence, never good evidence.</p>
 */
@Service
public class LearnerModelCalibrationService {

    static final int BIN_COUNT = 10;

    private final TelemetryEventRepository events;
    private final LearnerProperties properties;

    public LearnerModelCalibrationService(TelemetryEventRepository events,
                                          LearnerProperties properties) {
        this.events = events;
        this.properties = properties;
    }

    /**
     * One equal-width bin of the [0, 1] latent-forecast range. All means are 0.0 on
     * empty bins — a zero count is the honest signal, never an average of nothing.
     *
     * @param meanLatentPredicted mean raw latent forecast (decayedPrior) in this bin
     * @param meanPredicted       mean EMISSION-mapped prediction — the headline the
     *                            Brier/ECE are computed on
     * @param observedAccuracy    fraction of rows in this bin that were correct
     * @param meanBrier           mean (predicted − outcome)² over the bin's rows
     * @param calibrationError    signed: meanPredicted − observedAccuracy
     */
    public record Bin(int index, double lowerBound, double upperBound,
                      long count, double meanLatentPredicted, double meanPredicted,
                      double observedAccuracy, double meanBrier, double calibrationError) {
    }

    /**
     * @param sampleCount rows aggregated (after nodeId filtering and malformed skips)
     * @param skippedRows rows dropped as malformed/legacy (missing or unparseable
     *                    {@code decayedPrior}/{@code correctness}, latent outside [0,1])
     * @param brier       mean (predicted − outcome)² over all sampled rows, on the
     *                    emission-mapped prediction
     * @param ece         expected calibration error Σ (count/n)·|meanPredicted − observed|
     * @param bins        all ten equal-width bins, including empty ones
     */
    public record CalibrationReport(long sampleCount, long skippedRows,
                                    double brier, double ece, List<Bin> bins) {
    }

    /**
     * Aggregates the full {@code BKT_UPDATED} stream. Cycle-1 scale (hundreds of rows)
     * makes a single ordered fetch the honest, simple read; paging is a follow-up when
     * the stream outgrows memory, not before.
     *
     * @param nodeIdFilter optional knowledge-graph node — when present, only rows for
     *                     that node are aggregated
     */
    @Transactional(readOnly = true)
    public CalibrationReport report(UUID nodeIdFilter) {
        List<TelemetryEvent> rows =
                events.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED);

        long skipped = 0;
        long sampled = 0;
        double brierSum = 0.0;
        double[] binLatentSum = new double[BIN_COUNT];
        double[] binPredictedSum = new double[BIN_COUNT];
        double[] binOutcomeSum = new double[BIN_COUNT];
        double[] binBrierSum = new double[BIN_COUNT];
        long[] binCount = new long[BIN_COUNT];

        for (TelemetryEvent row : rows) {
            Map<String, Object> payload = row.payload();
            Double latent = doubleValue(payload.get("decayedPrior"));
            Boolean correct = payload.get("correctness") instanceof Boolean b ? b : null;
            if (latent == null || correct == null || latent < 0.0 || latent > 1.0) {
                skipped++;   // legacy row (pre-C1 contract) or malformed — skipped honestly
                continue;
            }
            if (nodeIdFilter != null
                    && !nodeIdFilter.toString().equals(payload.get("nodeId"))) {
                continue;   // filtered, not malformed — counts in neither bucket
            }
            BktParams emission = properties.bkt().toParams(
                    payload.get("questionType") instanceof String t ? t : null,
                    payload.get("optionCount") instanceof Number n ? n.intValue() : 0);
            double predicted = latent * (1.0 - emission.slip())
                    + (1.0 - latent) * emission.guess();
            double outcome = correct ? 1.0 : 0.0;
            int bin = Math.min(BIN_COUNT - 1, Math.max(0, (int) Math.floor(latent * BIN_COUNT)));
            sampled++;
            brierSum += (predicted - outcome) * (predicted - outcome);
            binCount[bin]++;
            binLatentSum[bin] += latent;
            binPredictedSum[bin] += predicted;
            binOutcomeSum[bin] += outcome;
            binBrierSum[bin] += (predicted - outcome) * (predicted - outcome);
        }

        List<Bin> bins = new ArrayList<>(BIN_COUNT);
        for (int i = 0; i < BIN_COUNT; i++) {
            long n = binCount[i];
            double meanPredicted = n == 0 ? 0.0 : binPredictedSum[i] / n;
            double observed = n == 0 ? 0.0 : binOutcomeSum[i] / n;
            bins.add(new Bin(i, i / (double) BIN_COUNT, (i + 1) / (double) BIN_COUNT,
                    n,
                    n == 0 ? 0.0 : binLatentSum[i] / n,
                    meanPredicted,
                    observed,
                    n == 0 ? 0.0 : binBrierSum[i] / n,
                    meanPredicted - observed));
        }
        double ece = 0.0;
        for (Bin b : bins) {
            ece += sampled == 0 ? 0.0
                    : b.count() / (double) sampled * Math.abs(b.calibrationError());
        }
        return new CalibrationReport(sampled, skipped,
                sampled == 0 ? 0.0 : brierSum / sampled, ece, List.copyOf(bins));
    }

    private static Double doubleValue(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
