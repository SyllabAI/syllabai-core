package com.syllabai.research;

import com.syllabai.learner.LearnerProperties;
import com.syllabai.learner.bkt.BktParams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>The gap cut (C4 protocol §6 item 4; §2 gap axis).</b> The report carries
 * per-gap-band {@link GapSegment}s alongside the format segments: bands
 * {@code 0 / 1-30 / 31-90 / 91-365 / 366+} aligned to the τ bands, with zero-gap
 * as its own stratum — the bit-identity leg between the decayed and raw priors
 * (a zero-gap stratum that stops matching the raw curve is a decay-path regression
 * alarm). Each gap segment additionally carries {@code meanAnchor}, the mean raw
 * ADR-031 anchor ({@code priorMastery}) over its rows: within zero-gap it is the
 * raw side of that leg, and its divergence from the decayed latent mean IS the
 * alarm's readout. The zero-gap stratum composes two sub-populations the recorded
 * {@code gapDays} cannot separate: first-practice rows (no prior practice — the
 * prior is the raw anchor, exact identity) and same-day re-practice rows (a gap
 * &lt; 1 day records as 0; sub-day decay applies, so identity holds only
 * approximately) — a healthy divergence is therefore bounded by same-day decay
 * alone. Rows whose {@code gapDays} is missing/unparseable/negative on an
 * otherwise-contract payload land in {@code UNKNOWN} (defensive tail — the
 * publisher always writes {@code gapDays} beside {@code decayedPrior}, so a
 * populated UNKNOWN stratum is itself a finding). Gap segments partition the
 * contract rows exactly as the format segments do; skipped/legacy rows stay
 * global and are never attributed (pre-C1 rows carry no honest gap either).
 * The axes are cut separately — no format×gap cross-tab yet; add one only when
 * a review needs it.</p>
 *
 * <p><b>Small-cell suppression on the wire (C7 resolution, k-anonymity).</b> The
 * research endpoint spans all learners and is nodeId-filterable, so a band of a
 * few rows could be cross-read against class surfaces to infer an individual
 * (ADR-033 challenge C7's three-way choice: ADMIN-only, class-scoped, or
 * k-anonymity). Decided 2026-10-02: <b>k-anonymity enforced by the API</b>.
 * ADMIN-only narrows the audience without closing the vector (an admin holds
 * every class surface and is exactly the nodeId-slice forensics user);
 * class-scoping would destroy the measurement (calibration is a property of the
 * model, not of a class) while generating the tiniest cells in the system. The
 * rule: any cell of the report — the pooled headline, a pooled bin, a format or
 * gap segment, a bin within a segment — whose <b>distinct learners number fewer
 * than {@link #MIN_REPORTABLE_LEARNERS}</b> renders its statistics as
 * {@code null} with {@code suppressed: true}; counts stay visible everywhere
 * (sampleCount, learnerCount, bin counts, skippedRows) so the partition
 * invariants, the coverage rule, and the audit of WHY a cell is hidden all
 * survive: counts stay, outcomes go. The unit is the learner, not the row — one
 * marked attempt updates every node it honestly tests, so a single learner can
 * place several rows in one bin and a row-count floor would pass a 6-row/
 * 2-learner cell; the row's {@code learnerId} (non-null by the telemetry
 * contract) makes the true unit countable. The n &lt; 5 row floor of the C4
 * protocol's §4.2 stays as the coarser citation guard. k = 5 is a code constant,
 * deliberately not configuration — a privacy floor an environment variable could
 * silently lower would not be a floor. Empty cells (count 0) keep the established
 * honest-zero rendering and are distinct from suppressed ones (count &gt; 0,
 * nulls). A reportable segment's aggregate Brier/ECE still includes its
 * suppressed bins' contributions (excluding them would select-bias the
 * statistic); only the fine-grained cells hide. The nodeId-filtered slice
 * inherits the rule unchanged (same code path).</p>
 *
 * <p><b>Known limits, stated here on purpose.</b> Samples are node-outcomes, not
 * independent learners (one marked attempt updates every node it honestly tests) —
 * the report is descriptive, not i.i.d. statistics, and carries no confidence
 * intervals. The mapped prediction uses the CURRENT configured slip, so slip
 * retunes change the mapping across the comparison (guess is per-row and immune);
 * segment any such comparison. Traffic per bin ({@code count}) is part of the
 * report: an empty bin is no evidence, never good evidence.</p>
 */
@Service
public class LearnerModelCalibrationService {

    static final int BIN_COUNT = 10;

    /**
     * The C7 suppression floor: a report cell aggregates at least this many DISTINCT
     * learners before its statistics leave the wire. Chosen k = 5 per the C4
     * protocol's §4.2 small-cell floor (which it upgrades from a citation rule to an
     * enforced one); deliberately a constant, not configuration — a privacy floor
     * that can be silently lowered by an environment variable is not a floor.
     * Moving it is a reviewed code change with the ADR ledger updated.
     */
    static final int MIN_REPORTABLE_LEARNERS = 5;

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
     * The gap axis in fixed render order (C4 protocol §2): τ-aligned bands with
     * zero-gap as its own stratum. Fixed so a report's segment list is diffable
     * across time and the coverage annotation is trivial.
     */
    static final List<String> GAP_ORDER = List.of(
            "0", "1-30", "31-90", "91-365", "366+", "UNKNOWN");

    private static final String UNKNOWN_GAP = "UNKNOWN";

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
     * The gap-band key a contract row belongs to (C4 protocol §2 gap axis) — the
     * {@code gapDays} the update path computed alongside the decay, banded per the
     * τ alignment. Pure and total: every row maps to exactly one of
     * {@link #GAP_ORDER}'s keys, so the gap segments partition by construction.
     * Missing/unparseable/negative values land in {@code UNKNOWN}: the publisher
     * always writes {@code gapDays} beside {@code decayedPrior}, so a populated
     * UNKNOWN stratum is a payload-contract finding, not a normal tail.
     */
    static String gapSegmentKey(Object gapDays) {
        Long g = longValue(gapDays);
        if (g == null || g < 0) {
            return UNKNOWN_GAP;
        }
        if (g == 0) {
            return "0";
        }
        if (g <= 30) {
            return "1-30";
        }
        if (g <= 90) {
            return "31-90";
        }
        if (g <= 365) {
            return "91-365";
        }
        return "366+";
    }

    /**
     * One equal-width bin of the [0, 1] latent-forecast range, in one of three
     * states. <b>Empty</b> ({@code count == 0}): all means 0.0 — a zero count is the
     * honest signal, never an average of nothing. <b>Suppressed</b> (C7: count &gt; 0
     * but fewer than {@link #MIN_REPORTABLE_LEARNERS} distinct learners): all means
     * {@code null} — the cell's outcomes hide, its traffic stays visible.
     * <b>Reportable</b>: the full statistics. A {@code null} mean must never be
     * read as zero, and a zero mean on a zero count must never be read as data.
     *
     * @param meanLatentPredicted mean raw latent forecast (decayedPrior) in this bin
     * @param meanPredicted       mean EMISSION-mapped prediction — the headline the
     *                            Brier/ECE are computed on
     * @param observedAccuracy    fraction of rows in this bin that were correct
     * @param meanBrier           mean (predicted − outcome)² over the bin's rows
     * @param calibrationError    signed: meanPredicted − observedAccuracy
     */
    public record Bin(int index, double lowerBound, double upperBound,
                      long count, Double meanLatentPredicted, Double meanPredicted,
                      Double observedAccuracy, Double meanBrier, Double calibrationError) {
    }

    /**
     * One slice of the stream along the format axis (C4 protocol §2), with the same
     * statistics the pooled report carries, computed within the slice. Three states:
     * empty ({@code sampleCount == 0}, honest zeros, {@code suppressed == false} —
     * an empty segment is no evidence, never hidden evidence), suppressed (C7:
     * {@code learnerCount} in (0, {@link #MIN_REPORTABLE_LEARNERS}) — statistics
     * {@code null}, traffic visible), reportable (full statistics).
     *
     * @param segment     the fixed taxonomy key ({@link #SEGMENT_ORDER})
     * @param sampleCount rows in this segment (segments partition the contract rows;
     *                    visible even when suppressed, so the partition invariant holds)
     * @param brier       mean (predicted − outcome)² within the segment; null when suppressed
     * @param ece         Σ (count/n)·|meanPredicted − observed| within the segment;
     *                    null when suppressed
     * @param bins        all ten equal-width bins, including empty and suppressed ones
     * @param learnerCount distinct learners behind those rows — the C7 unit; visible
     *                    even when suppressed, so the suppression is auditable
     * @param suppressed  true when 0 &lt; learnerCount &lt; MIN_REPORTABLE_LEARNERS
     */
    public record FormatSegment(String segment, long sampleCount, Double brier, Double ece,
                                List<Bin> bins, long learnerCount, boolean suppressed) {
    }

    /**
     * One slice of the stream along the gap axis (C4 protocol §2), with the same
     * statistics the pooled report carries plus {@code meanAnchor} — the mean raw
     * ADR-031 anchor ({@code priorMastery}) over the slice's rows. Within the
     * zero-gap stratum the anchor mean is the raw side of the bit-identity leg: it
     * should sit at the decayed latent mean for first-practice rows and just above
     * it for same-day rows (sub-day decay); a wider divergence is the decay-path
     * regression alarm. Same three states as the format segments: empty (honest
     * zeros), suppressed (C7 — statistics null, traffic visible), reportable.
     *
     * @param segment     the fixed taxonomy key ({@link #GAP_ORDER})
     * @param sampleCount rows in this segment (segments partition the contract rows;
     *                    visible even when suppressed, so the partition invariant holds)
     * @param brier       mean (predicted − outcome)² within the segment; null when suppressed
     * @param ece         Σ (count/n)·|meanPredicted − observed| within the segment;
     *                    null when suppressed
     * @param meanAnchor  mean raw anchor ({@code priorMastery}) over the segment's rows;
     *                    null when suppressed
     * @param bins        all ten equal-width bins, including empty and suppressed ones
     * @param learnerCount distinct learners behind those rows — the C7 unit
     * @param suppressed  true when 0 &lt; learnerCount &lt; MIN_REPORTABLE_LEARNERS
     */
    public record GapSegment(String segment, long sampleCount, Double brier, Double ece,
                             Double meanAnchor, List<Bin> bins, long learnerCount,
                             boolean suppressed) {
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
     * @param bins        all ten equal-width bins, including empty and suppressed ones
     * @param segments    the per-format slices, in fixed {@link #SEGMENT_ORDER} order —
     *                    their sampleCounts always sum to {@code sampleCount}
     * @param gapSegments the per-gap-band slices, in fixed {@link #GAP_ORDER} order —
     *                    their sampleCounts also always sum to {@code sampleCount}
     * @param learnerCount distinct learners behind the sampled rows — the C7 unit;
     *                     the honest quantifier of the node-outcomes-not-learners caveat
     * @param suppressed  true when the whole report's distinct learners are fewer
     *                    than {@link #MIN_REPORTABLE_LEARNERS} (headline statistics
     *                    null; the per-bin suppression applies independently)
     */
    public record CalibrationReport(long sampleCount, long skippedRows,
                                    Double brier, Double ece, List<Bin> bins,
                                    List<FormatSegment> segments,
                                    List<GapSegment> gapSegments,
                                    long learnerCount, boolean suppressed) {
    }

    /**
     * Aggregates the full {@code BKT_UPDATED} stream in a single ordered fetch and a
     * single pass, accumulating the pooled statistics AND the per-format and
     * per-gap segments from the same row walk (C4 protocol §6: "group rows by
     * {@code questionType} in the same pass"). Cycle-1 scale (hundreds of rows) makes
     * this the honest, simple read; paging is a follow-up when the stream outgrows
     * memory, not before.
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
        Map<String, Accum> byGap = new LinkedHashMap<>();
        for (String key : GAP_ORDER) {
            byGap.put(key, new Accum());
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
            Double anchor = doubleValue(payload.get("priorMastery"));
            pooled.add(latent, predicted, outcome, brier, bin, anchor, row.learnerId());
            bySegment.get(segmentKey(type, optionCount))
                    .add(latent, predicted, outcome, brier, bin, anchor, row.learnerId());
            byGap.get(gapSegmentKey(payload.get("gapDays")))
                    .add(latent, predicted, outcome, brier, bin, anchor, row.learnerId());
        }

        List<FormatSegment> segments = new ArrayList<>(SEGMENT_ORDER.size());
        for (String key : SEGMENT_ORDER) {
            Accum a = bySegment.get(key);
            boolean suppressed = a.learnerCount() > 0
                    && a.learnerCount() < MIN_REPORTABLE_LEARNERS;
            segments.add(new FormatSegment(key, a.n,
                    suppressed ? null : a.brier(),
                    suppressed ? null : a.ece(),
                    a.bins(), a.learnerCount(), suppressed));
        }
        List<GapSegment> gapSegments = new ArrayList<>(GAP_ORDER.size());
        for (String key : GAP_ORDER) {
            Accum a = byGap.get(key);
            boolean suppressed = a.learnerCount() > 0
                    && a.learnerCount() < MIN_REPORTABLE_LEARNERS;
            gapSegments.add(new GapSegment(key, a.n,
                    suppressed ? null : a.brier(),
                    suppressed ? null : a.ece(),
                    suppressed ? null : a.anchor(),
                    a.bins(), a.learnerCount(), suppressed));
        }
        boolean pooledSuppressed = pooled.learnerCount() > 0
                && pooled.learnerCount() < MIN_REPORTABLE_LEARNERS;
        return new CalibrationReport(pooled.n, skipped,
                pooledSuppressed ? null : pooled.brier(),
                pooledSuppressed ? null : pooled.ece(),
                pooled.bins(), List.copyOf(segments), List.copyOf(gapSegments),
                pooled.learnerCount(), pooledSuppressed);
    }

    /** Row accumulator for one population (pooled, a format segment, or a gap band). */
    private static final class Accum {
        private long n;
        private final long[] binCount = new long[BIN_COUNT];
        private final double[] latentSum = new double[BIN_COUNT];
        private final double[] predictedSum = new double[BIN_COUNT];
        private final double[] outcomeSum = new double[BIN_COUNT];
        private double brierSum;
        private final double[] binBrierSum = new double[BIN_COUNT];
        private double anchorSum;
        private long anchorN;
        private final Set<UUID> learners = new HashSet<>();
        private final List<Set<UUID>> binLearners = new ArrayList<>(BIN_COUNT);

        private Accum() {
            for (int i = 0; i < BIN_COUNT; i++) {
                binLearners.add(new HashSet<>());
            }
        }

        private void add(double latent, double predicted, double outcome, double brier,
                         int bin, Double anchor, UUID learnerId) {
            n++;
            brierSum += brier;
            binCount[bin]++;
            latentSum[bin] += latent;
            predictedSum[bin] += predicted;
            outcomeSum[bin] += outcome;
            binBrierSum[bin] += brier;
            if (anchor != null) {
                anchorSum += anchor;
                anchorN++;
            }
            learners.add(learnerId);
            binLearners.get(bin).add(learnerId);
        }

        private long learnerCount() {
            return learners.size();
        }

        /** Mean raw anchor over the rows carrying one — the bit-identity leg's raw side. */
        private double anchor() {
            return anchorN == 0 ? 0.0 : anchorSum / anchorN;
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
                double lower = i / (double) BIN_COUNT;
                double upper = (i + 1) / (double) BIN_COUNT;
                if (count == 0) {
                    // empty: the established honest-zero rendering — no evidence, not
                    // hidden evidence (distinct from C7 suppression by the zero count)
                    bins.add(new Bin(i, lower, upper, 0, 0.0, 0.0, 0.0, 0.0, 0.0));
                } else if (binLearners.get(i).size() < MIN_REPORTABLE_LEARNERS) {
                    // suppressed (C7): the cell's outcomes hide, its traffic stays
                    bins.add(new Bin(i, lower, upper, count, null, null, null, null, null));
                } else {
                    double meanPredicted = predictedSum[i] / count;
                    bins.add(new Bin(i, lower, upper, count,
                            latentSum[i] / count,
                            meanPredicted,
                            outcomeSum[i] / count,
                            binBrierSum[i] / count,
                            meanPredicted - outcomeSum[i] / count));
                }
            }
            return bins;
        }
    }

    /** Mirrors {@link #doubleValue(Object)}'s defensive posture for the integral gapDays. */
    private static Long longValue(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
