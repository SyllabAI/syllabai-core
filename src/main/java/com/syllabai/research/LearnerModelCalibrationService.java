package com.syllabai.research;

import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.bkt.BktParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * <p><b>The format cut (C4 protocol §6, first implementation ask).</b> The report
 * carries per-format {@link FormatSegment}s alongside the pooled numbers, so the
 * protocol's mandatory format axis (its §2) is expressible in one call:
 * {@code MCQ_SINGLE} bucketed by {@code optionCount} ({2-3, 4, 5+, malformed — the
 * malformed stratum makes the C3 guard's paper-path degradation observable}),
 * {@code SHORT_ANSWER}, {@code STRUCTURED}, and {@code UNTYPED} (null/blank/
 * unrecognized names — priced by the paper guess constant exactly as the update
 * path prices them, so the fold is pricing-faithful). Segments partition the
 * contract rows — pooled {@code sampleCount} is always the sum of the segment
 * counts — and render in a fixed taxonomy order, empty segments included with
 * honest zeros (an empty segment is no evidence, per the protocol's coverage rule).
 * Skipped/legacy rows are counted globally and never attributed to a segment:
 * pre-C1 rows carry no honest format, and per-format floors (the protocol's §3.1)
 * key on segment counts, so attributing them would flatter the cells. One caveat
 * the protocol's tight floor covers: the {2-3} bucket mixes two guess constants
 * (1/2 and 1/3) — a rare-tail stratum to be read directionally until it earns a
 * split.</p>
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
     * The format axis in fixed render order (C4 protocol §2). Fixed so a report's
     * segment list is diffable across time and the coverage annotation is trivial.
     */
    static final List<String> SEGMENT_ORDER = List.of(
            "MCQ_SINGLE(2-3)", "MCQ_SINGLE(4)", "MCQ_SINGLE(5+)", "MCQ_SINGLE(malformed)",
            "SHORT_ANSWER", "STRUCTURED", "UNTYPED");

    private static final String UNTYPED = "UNTYPED";

    /**
     * The segment key a contract row belongs to — the same (questionType,
     * optionCount) the update path's resolver priced it with, bucketed per the C4
     * protocol's format axis. Pure and total: every row maps to exactly one of
     * {@link #SEGMENT_ORDER}'s keys, so segments partition by construction.
     * Unrecognized format names fold into {@code UNTYPED} because the resolver
     * prices them identically (paper guess constant) — the fold never mixes
     * different pricing.
     */
    static String segmentKey(String questionType, int optionCount) {
        if (questionType == null || questionType.isBlank()) {
            return UNTYPED;
        }
        return switch (questionType) {
            case "MCQ_SINGLE" -> optionCount >= 5 ? "MCQ_SINGLE(5+)"
                    : optionCount >= 2 ? (optionCount == 4 ? "MCQ_SINGLE(4)" : "MCQ_SINGLE(2-3)")
                    : "MCQ_SINGLE(malformed)";
            case "SHORT_ANSWER" -> "SHORT_ANSWER";
            case "STRUCTURED" -> "STRUCTURED";
            default -> UNTYPED;
        };
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
     * One slice of the stream along the format axis (C4 protocol §2), with the same
     * statistics the pooled report carries, computed within the slice. An empty
     * segment renders with sampleCount 0 and honest zeros — it is part of the
     * report precisely so a review can say "no evidence" instead of guessing.
     *
     * @param segment     the fixed taxonomy key ({@link #SEGMENT_ORDER})
     * @param sampleCount rows in this segment (segments partition the contract rows)
     * @param brier       mean (predicted − outcome)² within the segment
     * @param ece         Σ (count/n)·|meanPredicted − observed| within the segment
     * @param bins        all ten equal-width bins, including empty ones
     */
    public record FormatSegment(String segment, long sampleCount, double brier, double ece,
                                List<Bin> bins) {
    }

    /**
     * @param sampleCount rows aggregated (after nodeId filtering and malformed skips)
     * @param skippedRows rows dropped as malformed/legacy (missing or unparseable
     *                    {@code decayedPrior}/{@code correctness}, latent outside [0,1]) —
     *                    counted globally, never attributed to a segment (pre-contract
     *                    rows carry no honest format)
     * @param brier       mean (predicted − outcome)² over all sampled rows, on the
     *                    emission-mapped prediction
     * @param ece         expected calibration error Σ (count/n)·|meanPredicted − observed|
     * @param bins        all ten equal-width bins, including empty ones
     * @param segments    the per-format slices, in fixed {@link #SEGMENT_ORDER} order —
     *                    their sampleCounts always sum to {@code sampleCount}
     */
    public record CalibrationReport(long sampleCount, long skippedRows,
                                    double brier, double ece, List<Bin> bins,
                                    List<FormatSegment> segments) {
    }

    /**
     * Aggregates the full {@code BKT_UPDATED} stream in a single ordered fetch and a
     * single pass, accumulating the pooled statistics AND the per-format segments
     * from the same row walk (C4 protocol §6: "group rows by {@code questionType} in
     * the same pass"). Cycle-1 scale (hundreds of rows) makes this the honest, simple
     * read; paging is a follow-up when the stream outgrows memory, not before.
     *
     * @param nodeIdFilter optional knowledge-graph node — when present, only rows for
     *                     that node are aggregated, and the segments cut the same rows
     */
    @Transactional(readOnly = true)
    public CalibrationReport report(UUID nodeIdFilter) {
        List<TelemetryEvent> rows =
                events.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED);

        Accum pooled = new Accum();
        Map<String, Accum> bySegment = new LinkedHashMap<>();
        for (String key : SEGMENT_ORDER) {
            bySegment.put(key, new Accum());
        }

        long skipped = 0;
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
            String type = payload.get("questionType") instanceof String t ? t : null;
            int optionCount = payload.get("optionCount") instanceof Number n ? n.intValue() : 0;
            BktParams emission = properties.bkt().toParams(type, optionCount);
            double predicted = latent * (1.0 - emission.slip())
                    + (1.0 - latent) * emission.guess();
            double outcome = correct ? 1.0 : 0.0;
            double brier = (predicted - outcome) * (predicted - outcome);
            int bin = Math.min(BIN_COUNT - 1, Math.max(0, (int) Math.floor(latent * BIN_COUNT)));
            pooled.add(latent, predicted, outcome, brier, bin);
            bySegment.get(segmentKey(type, optionCount))
                    .add(latent, predicted, outcome, brier, bin);
        }

        List<FormatSegment> segments = new ArrayList<>(SEGMENT_ORDER.size());
        for (String key : SEGMENT_ORDER) {
            segments.add(new FormatSegment(key, bySegment.get(key).n,
                    bySegment.get(key).brier(), bySegment.get(key).ece(),
                    bySegment.get(key).bins()));
        }
        return new CalibrationReport(pooled.n, skipped, pooled.brier(), pooled.ece(),
                pooled.bins(), List.copyOf(segments));
    }

    /** Row accumulator for one population (the pooled stream, or one format segment). */
    private static final class Accum {
        private long n;
        private final long[] binCount = new long[BIN_COUNT];
        private final double[] latentSum = new double[BIN_COUNT];
        private final double[] predictedSum = new double[BIN_COUNT];
        private final double[] outcomeSum = new double[BIN_COUNT];
        private double brierSum;
        private final double[] binBrierSum = new double[BIN_COUNT];

        private void add(double latent, double predicted, double outcome, double brier, int bin) {
            n++;
            brierSum += brier;
            binCount[bin]++;
            latentSum[bin] += latent;
            predictedSum[bin] += predicted;
            outcomeSum[bin] += outcome;
            binBrierSum[bin] += brier;
        }

        private double brier() {
            return n == 0 ? 0.0 : brierSum / n;
        }

        private double ece() {
            double ece = 0.0;
            for (int i = 0; i < BIN_COUNT; i++) {
                if (binCount[i] == 0) {
                    continue;
                }
                double meanPredicted = predictedSum[i] / binCount[i];
                double observed = outcomeSum[i] / binCount[i];
                ece += binCount[i] / (double) n * Math.abs(meanPredicted - observed);
            }
            return ece;
        }

        private List<Bin> bins() {
            List<Bin> bins = new ArrayList<>(BIN_COUNT);
            for (int i = 0; i < BIN_COUNT; i++) {
                long count = binCount[i];
                double meanPredicted = count == 0 ? 0.0 : predictedSum[i] / count;
                double observed = count == 0 ? 0.0 : outcomeSum[i] / count;
                bins.add(new Bin(i, i / (double) BIN_COUNT, (i + 1) / (double) BIN_COUNT,
                        count,
                        count == 0 ? 0.0 : latentSum[i] / count,
                        meanPredicted,
                        observed,
                        count == 0 ? 0.0 : binBrierSum[i] / count,
                        meanPredicted - observed));
            }
            return bins;
        }
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
