package com.syllabai.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.learner.LearnerProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Calibration instrument pins (S2/ADR-033, challenge C2; C4 protocol; C7 resolution).
 * Every expected number is hand-derived independently of the implementation: per-row
 * mapped prediction latent·(1−slip) + (1−latent)·guess (slip 0.1; guess 0.01
 * STRUCTURED / 0.05 SHORT_ANSWER / 0.25 MCQ-4 / 0.25 untyped), bins on the latent
 * forecast, Brier on the mapped prediction, ECE = Σ (count/n)·|meanPredicted −
 * observed|.
 *
 * <p>Suppression pins (C7, k-anonymity on the wire): the unit is the distinct
 * LEARNER, not the row — one marked attempt updates every node it honestly tests,
 * so rows concentrate per learner and a row-count floor would pass a 6-row/
 * 2-learner cell. Cells with fewer than 5 distinct learners render their statistics
 * null while all counts stay visible; empty cells (count 0) keep the honest-zero
 * rendering and are distinct from suppressed ones. The default fixture rows are
 * each their own learner (distinct-learner counts equal row counts); the dense
 * "reportable" fixture controls learners explicitly.</p>
 */
class LearnerModelCalibrationServiceTest {

    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_OTHER = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-09-03T12:00:00Z");

    // the dense fixture's learners (L7 deliberately reused across axes)
    private static final UUID L1 = UUID.randomUUID();
    private static final UUID L2 = UUID.randomUUID();
    private static final UUID L3 = UUID.randomUUID();
    private static final UUID L4 = UUID.randomUUID();
    private static final UUID L5 = UUID.randomUUID();
    private static final UUID L6 = UUID.randomUUID();
    private static final UUID L7 = UUID.randomUUID();
    private static final UUID L8 = UUID.randomUUID();
    private static final UUID L9 = UUID.randomUUID();
    private static final UUID L10 = UUID.randomUUID();
    private static final UUID L11 = UUID.randomUUID();
    private static final UUID L12 = UUID.randomUUID();
    private static final UUID L13 = UUID.randomUUID();
    private static final UUID L14 = UUID.randomUUID();

    private TelemetryEventRepository repository;
    private LearnerModelCalibrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(TelemetryEventRepository.class);
        service = new LearnerModelCalibrationService(repository,
                new LearnerProperties(null, null, null, null, null));
    }

    private TelemetryEvent bktRow(UUID nodeId, Object decayedPrior, Object correctness,
                                  String questionType, int optionCount) {
        // first-practice shape by default: gap 0, anchor == decayedPrior (the exact
        // bit-identity leg), and each row its own learner (distinct counts == row
        // counts) — suppression-focused and dense fixtures override all three
        return bktRow(UUID.randomUUID(), nodeId, decayedPrior, correctness,
                questionType, optionCount, 0L,
                decayedPrior instanceof Number n ? n.doubleValue() : null);
    }

    private TelemetryEvent bktRow(UUID nodeId, Object decayedPrior, Object correctness,
                                  String questionType, int optionCount, Object gapDays,
                                  Object anchor) {
        return bktRow(UUID.randomUUID(), nodeId, decayedPrior, correctness,
                questionType, optionCount, gapDays, anchor);
    }

    private TelemetryEvent bktRow(UUID learner, UUID nodeId, Object decayedPrior,
                                  Object correctness, String questionType, int optionCount,
                                  Object gapDays, Object anchor) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeId", nodeId.toString());
        if (decayedPrior != null) {
            payload.put("decayedPrior", decayedPrior);
        }
        payload.put("correctness", correctness);
        payload.put("questionType", questionType);
        payload.put("optionCount", optionCount);
        payload.put("gapDays", gapDays);
        if (anchor != null) {
            payload.put("priorMastery", anchor);
        }
        return new TelemetryEvent(learner,
                TelemetryEvent.Type.BKT_UPDATED, payload, WHEN);
    }

    // the six NODE_A rows + the one NODE_OTHER row of the hand-computed fixture
    private List<TelemetryEvent> fullStream() {
        return List.of(
                bktRow(NODE_A, 0.95, true, "STRUCTURED", 0),
                bktRow(NODE_A, 0.93, true, "STRUCTURED", 0),
                bktRow(NODE_A, 0.91, false, "STRUCTURED", 0),
                bktRow(NODE_A, 0.35, false, "MCQ_SINGLE", 4),
                bktRow(NODE_A, 0.32, true, "MCQ_SINGLE", 4),
                bktRow(NODE_A, 0.50, true, null, 0),          // untyped → paper guess 0.25
                bktRow(NODE_OTHER, 0.80, true, "STRUCTURED", 0),
                // malformed/legacy rows — skipped honestly and counted
                bktRow(NODE_A, null, true, "STRUCTURED", 0),  // legacy: no decayedPrior
                bktRow(NODE_A, 0.44, "yes", "STRUCTURED", 0), // correctness not a boolean
                bktRow(NODE_A, 1.7, true, "STRUCTURED", 0));  // latent outside [0,1]
    }

    @Test
    @DisplayName("report over the full stream: headline Brier/ECE on the EMISSION-mapped "
            + "prediction; every populated bin suppresses (1-3 learners each) while counts stay")
    void fullStreamReport() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isEqualTo(7);
        assertThat(report.skippedRows()).isEqualTo(3);
        assertThat(report.learnerCount()).isEqualTo(7);   // default fixture: one learner per row
        assertThat(report.suppressed()).isFalse();        // 7 >= 5 → the headline reports
        assertThat(report.brier()).isCloseTo(0.2141624, within(1e-9));
        assertThat(report.ece()).isCloseTo(0.18294285714285716, within(1e-9));

        // C7 in one glance: every populated bin hides its outcomes (2/1/1/3 distinct
        // learners), its traffic stays — counts stay, outcomes go
        LearnerModelCalibrationService.Bin bin3 = report.bins().get(3);
        assertThat(bin3.count()).isEqualTo(2);
        assertThat(bin3.meanLatentPredicted()).isNull();
        assertThat(bin3.meanPredicted()).isNull();
        assertThat(bin3.observedAccuracy()).isNull();
        assertThat(bin3.meanBrier()).isNull();
        assertThat(bin3.calibrationError()).isNull();

        LearnerModelCalibrationService.Bin bin5 = report.bins().get(5);
        assertThat(bin5.count()).isEqualTo(1);
        assertThat(bin5.meanPredicted()).isNull();
        LearnerModelCalibrationService.Bin bin8 = report.bins().get(8);
        assertThat(bin8.count()).isEqualTo(1);
        assertThat(bin8.meanPredicted()).isNull();
        LearnerModelCalibrationService.Bin bin9 = report.bins().get(9);
        assertThat(bin9.count()).isEqualTo(3);
        assertThat(bin9.meanLatentPredicted()).isNull();
        assertThat(bin9.observedAccuracy()).isNull();

        // empty bins keep the honest-zero rendering — distinct from suppression
        LearnerModelCalibrationService.Bin bin0 = report.bins().get(0);
        assertThat(bin0.count()).isZero();
        assertThat(bin0.meanPredicted()).isZero();          // 0.0, not null
        assertThat(bin0.observedAccuracy()).isZero();
    }

    @Test
    @DisplayName("nodeId filter excludes other nodes without counting them as malformed")
    void nodeIdFilter() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(NODE_A);

        assertThat(report.sampleCount()).isEqualTo(6);   // NODE_OTHER row filtered out
        assertThat(report.skippedRows()).isEqualTo(3);   // still only the malformed three
        assertThat(report.learnerCount()).isEqualTo(6);
        assertThat(report.suppressed()).isFalse();
        assertThat(report.brier()).isCloseTo(0.2369754666666667, within(1e-9));
        assertThat(report.ece()).isCloseTo(0.16710000000000003, within(1e-9));
        assertThat(report.bins().get(8).count()).isZero();   // the 0.80 row lived on NODE_OTHER
    }

    @Test
    @DisplayName("empty stream: an honest zero report, never NaN — empty is not suppressed")
    void honestEmptyReport() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isZero();
        assertThat(report.skippedRows()).isZero();
        assertThat(report.learnerCount()).isZero();
        assertThat(report.suppressed()).isFalse();   // nothing to hide — no evidence, not hidden evidence
        assertThat(report.brier()).isZero();
        assertThat(report.ece()).isZero();
        assertThat(report.bins()).hasSize(10).allSatisfy(b -> {
            assertThat(b.count()).isZero();
            assertThat(b.meanPredicted()).isZero();
            assertThat(b.observedAccuracy()).isZero();
        });
    }

    @Test
    @DisplayName("every malformed row is skipped and counted — none can poison the curve")
    void onlyMalformedRows() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        bktRow(NODE_A, "not-a-number", true, null, 0),
                        bktRow(NODE_A, 0.5, 42, null, 0),
                        bktRow(NODE_A, null, null, null, 0)));

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isZero();
        assertThat(report.skippedRows()).isEqualTo(3);
        assertThat(report.learnerCount()).isZero();
        assertThat(report.suppressed()).isFalse();
        assertThat(report.brier()).isZero();
        assertThat(report.ece()).isZero();
    }

    // ─── the format cut (C4 protocol §6, first implementation ask) ───────────

    private static LearnerModelCalibrationService.FormatSegment segment(
            LearnerModelCalibrationService.CalibrationReport report, String key) {
        return report.segments().stream()
                .filter(s -> s.segment().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the format cut under C7: segments keep their counts and partition "
            + "(counts stay) while every sub-5-learner segment's outcomes go null")
    void formatSegmentsSuppressSmallCells() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        // fixed taxonomy order, empty segments included — diffable across time
        assertThat(report.segments())
                .extracting(LearnerModelCalibrationService.FormatSegment::segment)
                .containsExactly("MCQ_SINGLE(2-3)", "MCQ_SINGLE(4)", "MCQ_SINGLE(5+)",
                        "MCQ_SINGLE(malformed)", "SHORT_ANSWER", "STRUCTURED", "UNTYPED");

        // partition property SURVIVES suppression: the pooled sampleCount is still
        // the sum of the segment counts — counts stay, outcomes go
        assertThat(report.segments().stream()
                .mapToLong(LearnerModelCalibrationService.FormatSegment::sampleCount).sum())
                .isEqualTo(report.sampleCount());

        // STRUCTURED: 4 rows / 4 distinct learners → suppressed
        var structured = segment(report, "STRUCTURED");
        assertThat(structured.sampleCount()).isEqualTo(4);
        assertThat(structured.learnerCount()).isEqualTo(4);
        assertThat(structured.suppressed()).isTrue();
        assertThat(structured.brier()).isNull();
        assertThat(structured.ece()).isNull();
        assertThat(structured.bins()).hasSize(10).allSatisfy(b -> {
            if (b.count() > 0) {
                assertThat(b.meanPredicted()).isNull();
            }
        });

        // MCQ_SINGLE(4): 2/2 suppressed; UNTYPED: 1/1 suppressed
        var mcq4 = segment(report, "MCQ_SINGLE(4)");
        assertThat(mcq4.sampleCount()).isEqualTo(2);
        assertThat(mcq4.learnerCount()).isEqualTo(2);
        assertThat(mcq4.suppressed()).isTrue();
        assertThat(mcq4.brier()).isNull();
        var untyped = segment(report, "UNTYPED");
        assertThat(untyped.sampleCount()).isEqualTo(1);
        assertThat(untyped.learnerCount()).isEqualTo(1);
        assertThat(untyped.suppressed()).isTrue();
        assertThat(untyped.ece()).isNull();

        // SHORT_ANSWER: empty — honest zeros, suppressed FALSE (empty is not hidden evidence)
        var shortAnswer = segment(report, "SHORT_ANSWER");
        assertThat(shortAnswer.sampleCount()).isZero();
        assertThat(shortAnswer.learnerCount()).isZero();
        assertThat(shortAnswer.suppressed()).isFalse();
        assertThat(shortAnswer.brier()).isZero();
        assertThat(shortAnswer.ece()).isZero();
        assertThat(shortAnswer.bins()).hasSize(10).allSatisfy(
                b -> assertThat(b.count()).isZero());
    }

    @Test
    @DisplayName("MCQ optionCount buckets {2-3, 4, 5+, malformed}; unrecognized or blank "
            + "format names fold into UNTYPED — the fold never mixes different pricing")
    void mcqBucketAndUntypedPins() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        bktRow(NODE_A, 0.60, true, "MCQ_SINGLE", 2),
                        bktRow(NODE_A, 0.55, false, "MCQ_SINGLE", 3),
                        bktRow(NODE_A, 0.70, true, "MCQ_SINGLE", 5),
                        bktRow(NODE_A, 0.40, true, "MCQ_SINGLE", 1),  // C3 guard degrades to paper path
                        bktRow(NODE_A, 0.44, true, "MCQ_SINGLE", 0),  // absent/zero count → malformed
                        bktRow(NODE_A, 0.66, true, "TRUE_FALSE", 0),  // unrecognized → paper pricing
                        bktRow(NODE_A, 0.52, false, "  ", 0)));       // blank → UNTYPED

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isEqualTo(7);
        assertThat(segment(report, "MCQ_SINGLE(2-3)").sampleCount()).isEqualTo(2);
        assertThat(segment(report, "MCQ_SINGLE(4)").sampleCount()).isZero();
        assertThat(segment(report, "MCQ_SINGLE(5+)").sampleCount()).isEqualTo(1);
        assertThat(segment(report, "MCQ_SINGLE(malformed)").sampleCount()).isEqualTo(2);
        assertThat(segment(report, "SHORT_ANSWER").sampleCount()).isZero();
        assertThat(segment(report, "STRUCTURED").sampleCount()).isZero();
        assertThat(segment(report, "UNTYPED").sampleCount()).isEqualTo(2);
        assertThat(report.segments().stream()
                .mapToLong(LearnerModelCalibrationService.FormatSegment::sampleCount).sum())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("the nodeId filter cuts the segments too — a node slice is its own report")
    void nodeIdFilterCutsSegments() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(NODE_OTHER);

        assertThat(report.sampleCount()).isEqualTo(1);
        assertThat(segment(report, "STRUCTURED").sampleCount()).isEqualTo(1);
        assertThat(segment(report, "MCQ_SINGLE(4)").sampleCount()).isZero();
        assertThat(segment(report, "UNTYPED").sampleCount()).isZero();
        assertThat(report.segments().stream()
                .mapToLong(LearnerModelCalibrationService.FormatSegment::sampleCount).sum())
                .isEqualTo(1);
    }

    // ─── the gap cut (C4 protocol §6 item 4; §2 gap axis) ─────────────────

    private static LearnerModelCalibrationService.GapSegment gapSegment(
            LearnerModelCalibrationService.CalibrationReport report, String key) {
        return report.gapSegments().stream()
                .filter(s -> s.segment().equals(key))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the gap cut under C7: bands keep their counts and partition while "
            + "every sub-5-learner band's outcomes (incl. meanAnchor) go null")
    void gapSegmentsSuppressSmallCells() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        bktRow(NODE_A, 0.95, true, "STRUCTURED", 0, 0L, 0.95),
                        bktRow(NODE_A, 0.93, true, "STRUCTURED", 0, 45L, 0.96),
                        bktRow(NODE_A, 0.35, false, "MCQ_SINGLE", 4, 200L, 0.50),
                        bktRow(NODE_A, 0.32, true, "MCQ_SINGLE", 4, 400L, 0.40),
                        bktRow(NODE_A, 0.50, true, null, 0, 10L, 0.55),
                        bktRow(NODE_A, 0.80, true, "STRUCTURED", 0, 0L, 0.90),
                        bktRow(NODE_A, 0.60, false, "SHORT_ANSWER", 0, "abc", 0.62)));

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        // fixed taxonomy order, empty segments included — diffable across time
        assertThat(report.gapSegments())
                .extracting(LearnerModelCalibrationService.GapSegment::segment)
                .containsExactly("0", "1-30", "31-90", "91-365", "366+", "UNKNOWN");

        // partition on counts survives suppression
        assertThat(report.gapSegments().stream()
                .mapToLong(LearnerModelCalibrationService.GapSegment::sampleCount).sum())
                .isEqualTo(report.sampleCount());

        // zero-gap: 2 rows / 2 learners → suppressed, meanAnchor hidden with the rest
        var zero = gapSegment(report, "0");
        assertThat(zero.sampleCount()).isEqualTo(2);
        assertThat(zero.learnerCount()).isEqualTo(2);
        assertThat(zero.suppressed()).isTrue();
        assertThat(zero.brier()).isNull();
        assertThat(zero.ece()).isNull();
        assertThat(zero.meanAnchor()).isNull();

        // every single-learner band: suppressed, count visible
        assertThat(gapSegment(report, "1-30").sampleCount()).isEqualTo(1);
        assertThat(gapSegment(report, "1-30").suppressed()).isTrue();
        assertThat(gapSegment(report, "31-90").suppressed()).isTrue();
        assertThat(gapSegment(report, "91-365").suppressed()).isTrue();
        assertThat(gapSegment(report, "366+").suppressed()).isTrue();
        assertThat(gapSegment(report, "UNKNOWN").sampleCount()).isEqualTo(1);
        assertThat(gapSegment(report, "UNKNOWN").suppressed()).isTrue();
        assertThat(gapSegment(report, "UNKNOWN").meanAnchor()).isNull();
    }

    @Test
    @DisplayName("zero-gap bit-identity leg at segment level: anchor==decayedPrior rows "
            + "give meanAnchor 4.76/7 = 0.68 even while the per-bin curves hide")
    void zeroGapBitIdentityLeg() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        // every fixture row is first-practice shaped (anchor == decayedPrior, gap 0):
        // the segment is reportable (7 distinct learners) and the anchor mean is exact
        var zero = gapSegment(report, "0");
        assertThat(zero.sampleCount()).isEqualTo(7);
        assertThat(zero.learnerCount()).isEqualTo(7);
        assertThat(zero.suppressed()).isFalse();
        assertThat(zero.meanAnchor()).isCloseTo(0.68, within(1e-9));

        // the per-bin curves hide (3/2/1/1 learners per bin) — the fine-grained leg
        // readout needs density, which the reportable fixture pins end to end
        assertThat(zero.bins().get(9).count()).isEqualTo(3);
        assertThat(zero.bins().get(9).meanLatentPredicted()).isNull();

        // the other five bands render honest zeros — no evidence is not good evidence
        for (String key : List.of("1-30", "31-90", "91-365", "366+", "UNKNOWN")) {
            var s = gapSegment(report, key);
            assertThat(s.sampleCount()).isZero();
            assertThat(s.suppressed()).isFalse();
            assertThat(s.brier()).isZero();
            assertThat(s.meanAnchor()).isZero();
        }
    }

    @Test
    @DisplayName("the nodeId filter cuts the gap segments too")
    void nodeIdFilterCutsGapSegments() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(NODE_OTHER);

        assertThat(report.sampleCount()).isEqualTo(1);
        assertThat(gapSegment(report, "0").sampleCount()).isEqualTo(1);
        assertThat(gapSegment(report, "1-30").sampleCount()).isZero();
        assertThat(report.gapSegments().stream()
                .mapToLong(LearnerModelCalibrationService.GapSegment::sampleCount).sum())
                .isEqualTo(1);
    }

    // ─── C7 resolution: k-anonymity on the wire, the unit is the learner ───────

    @Test
    @DisplayName("the unit is the learner, not the row: 6 rows from 2 learners suppress "
            + "where a row-count floor would pass; 5 rows from 5 learners report")
    void theLearnerIsTheUnit() {
        // same bin, same latents, two learner distributions — one attempt updates
        // every node it honestly tests, so rows concentrate per learner
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        bktRow(L1, NODE_A, 0.35, false, "MCQ_SINGLE", 4, 45L, 0.50),
                        bktRow(L1, NODE_A, 0.32, true, "MCQ_SINGLE", 4, 45L, 0.40),
                        bktRow(L2, NODE_A, 0.38, true, "MCQ_SINGLE", 4, 45L, 0.52),
                        bktRow(L1, NODE_A, 0.33, false, "MCQ_SINGLE", 4, 45L, 0.47),
                        bktRow(L2, NODE_A, 0.37, true, "MCQ_SINGLE", 4, 45L, 0.51),
                        bktRow(L1, NODE_A, 0.39, false, "MCQ_SINGLE", 4, 45L, 0.53)));

        LearnerModelCalibrationService.CalibrationReport concentrated = service.report(null);

        // 6 rows — a row-count floor of 5 would pass this cell; the learner-keyed
        // rule suppresses headline, segment and bin alike, counts visible
        assertThat(concentrated.sampleCount()).isEqualTo(6);
        assertThat(concentrated.learnerCount()).isEqualTo(2);
        assertThat(concentrated.suppressed()).isTrue();
        assertThat(concentrated.brier()).isNull();
        assertThat(concentrated.ece()).isNull();
        var mcq4 = segment(concentrated, "MCQ_SINGLE(4)");
        assertThat(mcq4.sampleCount()).isEqualTo(6);
        assertThat(mcq4.learnerCount()).isEqualTo(2);
        assertThat(mcq4.suppressed()).isTrue();
        assertThat(mcq4.brier()).isNull();
        assertThat(mcq4.bins().get(3).count()).isEqualTo(6);
        assertThat(mcq4.bins().get(3).meanPredicted()).isNull();

        // the same latents spread over 5 learners: reportable end to end
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        bktRow(L1, NODE_A, 0.35, false, "MCQ_SINGLE", 4, 45L, 0.50),
                        bktRow(L2, NODE_A, 0.32, true, "MCQ_SINGLE", 4, 45L, 0.40),
                        bktRow(L3, NODE_A, 0.38, true, "MCQ_SINGLE", 4, 45L, 0.52),
                        bktRow(L4, NODE_A, 0.31, false, "MCQ_SINGLE", 4, 45L, 0.44),
                        bktRow(L5, NODE_A, 0.39, true, "MCQ_SINGLE", 4, 45L, 0.58)));

        LearnerModelCalibrationService.CalibrationReport spread = service.report(null);

        assertThat(spread.sampleCount()).isEqualTo(5);
        assertThat(spread.learnerCount()).isEqualTo(5);
        assertThat(spread.suppressed()).isFalse();
        assertThat(spread.brier()).isCloseTo(0.24502875, within(1e-9));
        assertThat(spread.ece()).isCloseTo(0.1225, within(1e-9));
        var spreadMcq4 = segment(spread, "MCQ_SINGLE(4)");
        assertThat(spreadMcq4.suppressed()).isFalse();
        assertThat(spreadMcq4.brier()).isCloseTo(0.24502875, within(1e-9));
        assertThat(spreadMcq4.bins().get(3).count()).isEqualTo(5);
        assertThat(spreadMcq4.bins().get(3).meanLatentPredicted()).isCloseTo(0.35, within(1e-9));
        assertThat(spreadMcq4.bins().get(3).meanPredicted()).isCloseTo(0.4775, within(1e-9));
        assertThat(spreadMcq4.bins().get(3).observedAccuracy()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("reportable cells pin end to end on a learner-dense stream: pooled "
            + "bins, per-segment stats, per-band stats, the zero-gap leg, and suppressed "
            + "cells inside reportable segments")
    void reportableCellsPinEndToEnd() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of(
                        // STRUCTURED zero-gap bin9, exact identity (anchor == latent)
                        bktRow(L1, NODE_A, 0.95, true, "STRUCTURED", 0, 0L, 0.95),
                        bktRow(L2, NODE_A, 0.93, true, "STRUCTURED", 0, 0L, 0.93),
                        bktRow(L3, NODE_A, 0.91, false, "STRUCTURED", 0, 0L, 0.91),
                        bktRow(L4, NODE_A, 0.97, true, "STRUCTURED", 0, 0L, 0.97),
                        bktRow(L5, NODE_A, 0.99, false, "STRUCTURED", 0, 0L, 0.99),
                        // STRUCTURED zero-gap bin8, same-day (anchor > latent)
                        bktRow(L6, NODE_A, 0.80, true, "STRUCTURED", 0, 0L, 0.90),
                        bktRow(L7, NODE_A, 0.82, false, "STRUCTURED", 0, 0L, 0.92),
                        bktRow(L8, NODE_A, 0.85, true, "STRUCTURED", 0, 0L, 0.95),
                        bktRow(L9, NODE_A, 0.83, false, "STRUCTURED", 0, 0L, 0.93),
                        bktRow(L10, NODE_A, 0.86, true, "STRUCTURED", 0, 0L, 0.96),
                        // STRUCTURED bin4, gap 10 → 1-30 band (3 learners → suppressed band)
                        bktRow(L11, NODE_A, 0.45, true, "STRUCTURED", 0, 10L, 0.50),
                        bktRow(L12, NODE_A, 0.42, false, "STRUCTURED", 0, 10L, 0.48),
                        bktRow(L13, NODE_A, 0.49, true, "STRUCTURED", 0, 10L, 0.55),
                        // MCQ4 gap 45 → 31-90: 6 rows from 5 learners (L7 reused)
                        bktRow(L7, NODE_A, 0.35, false, "MCQ_SINGLE", 4, 45L, 0.50),
                        bktRow(L14, NODE_A, 0.32, true, "MCQ_SINGLE", 4, 45L, 0.40),
                        bktRow(L1, NODE_A, 0.38, true, "MCQ_SINGLE", 4, 45L, 0.52),
                        bktRow(L2, NODE_A, 0.31, false, "MCQ_SINGLE", 4, 45L, 0.44),
                        bktRow(L3, NODE_A, 0.39, true, "MCQ_SINGLE", 4, 45L, 0.58),
                        bktRow(L7, NODE_A, 0.36, true, "MCQ_SINGLE", 4, 45L, 0.55),
                        // UNTYPED gap 200 → 91-365: 5 learners bin5 + 1 UNKNOWN-gap row bin7
                        bktRow(L4, NODE_A, 0.50, true, null, 0, 200L, 0.55),
                        bktRow(L5, NODE_A, 0.52, true, null, 0, 200L, 0.58),
                        bktRow(L6, NODE_A, 0.54, false, null, 0, 200L, 0.60),
                        bktRow(L8, NODE_A, 0.56, true, null, 0, 200L, 0.62),
                        bktRow(L9, NODE_A, 0.58, true, null, 0, 200L, 0.64),
                        bktRow(L1, NODE_A, 0.70, true, null, 0, "abc", 0.76),
                        // SHORT_ANSWER gap 400 → 366+: 5 learners bin6 (the provisional 0.05 guess)
                        bktRow(L10, NODE_A, 0.60, false, "SHORT_ANSWER", 0, 400L, 0.66),
                        bktRow(L11, NODE_A, 0.62, false, "SHORT_ANSWER", 0, 400L, 0.68),
                        bktRow(L12, NODE_A, 0.64, true, "SHORT_ANSWER", 0, 400L, 0.70),
                        bktRow(L13, NODE_A, 0.66, true, "SHORT_ANSWER", 0, 400L, 0.72),
                        bktRow(L14, NODE_A, 0.68, false, "SHORT_ANSWER", 0, 400L, 0.74),
                        // malformed trio — skipped honestly, never attributed
                        bktRow(L1, NODE_A, null, true, "STRUCTURED", 0, 0L, null),
                        bktRow(L2, NODE_A, 0.44, "yes", "STRUCTURED", 0, 0L, 0.44),
                        bktRow(L3, NODE_A, 1.7, true, "STRUCTURED", 0, 0L, 1.7)));

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isEqualTo(30);
        assertThat(report.skippedRows()).isEqualTo(3);
        assertThat(report.learnerCount()).isEqualTo(14);
        assertThat(report.suppressed()).isFalse();
        assertThat(report.brier()).isCloseTo(0.2518828146666667, within(1e-9));
        assertThat(report.ece()).isCloseTo(0.20593333333333336, within(1e-9));

        // pooled bins: reportable where 5 learners gather, suppressed below —
        // one walk, both states, counts always visible
        var b3 = report.bins().get(3);
        assertThat(b3.count()).isEqualTo(6);
        assertThat(b3.meanLatentPredicted()).isCloseTo(0.3516666666666666, within(1e-9));
        assertThat(b3.meanPredicted()).isCloseTo(0.47858333333333336, within(1e-9));
        assertThat(b3.observedAccuracy()).isCloseTo(0.6666666666666666, within(1e-9));
        assertThat(b3.meanBrier()).isCloseTo(0.248566625, within(1e-9));
        assertThat(b3.calibrationError()).isCloseTo(-0.18808333333333327, within(1e-9));
        var b4 = report.bins().get(4);
        assertThat(b4.count()).isEqualTo(3);
        assertThat(b4.meanPredicted()).isNull();
        var b5 = report.bins().get(5);
        assertThat(b5.count()).isEqualTo(5);
        assertThat(b5.meanPredicted()).isCloseTo(0.601, within(1e-9));
        assertThat(b5.observedAccuracy()).isCloseTo(0.8, within(1e-9));
        assertThat(b5.meanBrier()).isCloseTo(0.19993899999999998, within(1e-9));
        var b6 = report.bins().get(6);
        assertThat(b6.count()).isEqualTo(5);
        assertThat(b6.meanPredicted()).isCloseTo(0.5940000000000001, within(1e-9));
        assertThat(b6.observedAccuracy()).isCloseTo(0.4, within(1e-9));
        assertThat(b6.calibrationError()).isCloseTo(0.19400000000000006, within(1e-9));
        var b7 = report.bins().get(7);
        assertThat(b7.count()).isEqualTo(1);
        assertThat(b7.meanPredicted()).isNull();
        var b8 = report.bins().get(8);
        assertThat(b8.count()).isEqualTo(5);
        assertThat(b8.meanLatentPredicted()).isCloseTo(0.8320000000000001, within(1e-9));
        assertThat(b8.meanPredicted()).isCloseTo(0.75048, within(1e-9));
        assertThat(b8.observedAccuracy()).isCloseTo(0.6, within(1e-9));
        assertThat(b8.meanBrier()).isCloseTo(0.258021428, within(1e-9));
        var b9 = report.bins().get(9);
        assertThat(b9.count()).isEqualTo(5);
        assertThat(b9.meanLatentPredicted()).isCloseTo(0.95, within(1e-9));
        assertThat(b9.meanPredicted()).isCloseTo(0.8554999999999999, within(1e-9));
        assertThat(b9.observedAccuracy()).isCloseTo(0.6, within(1e-9));
        assertThat(b9.meanBrier()).isCloseTo(0.30591393, within(1e-9));
        assertThat(b9.calibrationError()).isCloseTo(0.25549999999999995, within(1e-9));

        // format segments: reportable ones carry full stats; suppressed bins hide
        // INSIDE a reportable segment while the segment's aggregate keeps their
        // contribution (excluding them would select-bias the statistic)
        var mcq4 = segment(report, "MCQ_SINGLE(4)");
        assertThat(mcq4.sampleCount()).isEqualTo(6);
        assertThat(mcq4.learnerCount()).isEqualTo(5);
        assertThat(mcq4.suppressed()).isFalse();
        assertThat(mcq4.brier()).isCloseTo(0.248566625, within(1e-9));
        assertThat(mcq4.ece()).isCloseTo(0.18808333333333327, within(1e-9));
        var structured = segment(report, "STRUCTURED");
        assertThat(structured.sampleCount()).isEqualTo(13);
        assertThat(structured.learnerCount()).isEqualTo(13);
        assertThat(structured.suppressed()).isFalse();
        assertThat(structured.brier()).isCloseTo(0.27856113, within(1e-9));
        assertThat(structured.ece()).isCloseTo(0.2145769230769231, within(1e-9));
        assertThat(structured.bins().get(4).count()).isEqualTo(3);
        assertThat(structured.bins().get(4).meanPredicted()).isNull();   // suppressed inside
        assertThat(structured.bins().get(9).meanPredicted()).isCloseTo(0.8554999999999999, within(1e-9));
        var untyped = segment(report, "UNTYPED");
        assertThat(untyped.sampleCount()).isEqualTo(6);
        assertThat(untyped.learnerCount()).isEqualTo(6);
        assertThat(untyped.suppressed()).isFalse();
        assertThat(untyped.brier()).isCloseTo(0.18111999999999998, within(1e-9));
        assertThat(untyped.ece()).isCloseTo(0.21500000000000002, within(1e-9));
        assertThat(untyped.bins().get(7).count()).isEqualTo(1);
        assertThat(untyped.bins().get(7).meanPredicted()).isNull();
        var shortAnswer = segment(report, "SHORT_ANSWER");
        assertThat(shortAnswer.sampleCount()).isEqualTo(5);
        assertThat(shortAnswer.learnerCount()).isEqualTo(5);
        assertThat(shortAnswer.suppressed()).isFalse();
        assertThat(shortAnswer.brier()).isCloseTo(0.27141400000000004, within(1e-9));
        assertThat(shortAnswer.ece()).isCloseTo(0.19400000000000006, within(1e-9));
        assertThat(report.segments().stream()
                .mapToLong(LearnerModelCalibrationService.FormatSegment::sampleCount).sum())
                .isEqualTo(30);

        // gap segments: dense bands report, thin bands suppress
        var zero = gapSegment(report, "0");
        assertThat(zero.sampleCount()).isEqualTo(10);
        assertThat(zero.learnerCount()).isEqualTo(10);
        assertThat(zero.suppressed()).isFalse();
        assertThat(zero.brier()).isCloseTo(0.28196767899999997, within(1e-9));
        assertThat(zero.ece()).isCloseTo(0.20299, within(1e-9));
        // the bit-identity leg, reportable end to end: 5 exact-identity rows (bin9)
        // + 5 same-day rows (bin8, anchor>latent) → divergence exactly the same-day
        // mean, 0.05
        double weightedDecayed = 0.0;
        for (var bin : zero.bins()) {
            if (bin.count() > 0) {
                weightedDecayed += bin.count() * bin.meanLatentPredicted();
            }
        }
        assertThat(weightedDecayed / zero.sampleCount()).isCloseTo(0.891, within(1e-9));
        assertThat(zero.meanAnchor()).isCloseTo(0.941, within(1e-9));
        var band130 = gapSegment(report, "1-30");
        assertThat(band130.sampleCount()).isEqualTo(3);
        assertThat(band130.learnerCount()).isEqualTo(3);
        assertThat(band130.suppressed()).isTrue();
        assertThat(band130.brier()).isNull();
        assertThat(band130.meanAnchor()).isNull();
        var band3190 = gapSegment(report, "31-90");
        assertThat(band3190.sampleCount()).isEqualTo(6);
        assertThat(band3190.learnerCount()).isEqualTo(5);
        assertThat(band3190.suppressed()).isFalse();
        assertThat(band3190.ece()).isCloseTo(0.18808333333333327, within(1e-9));
        assertThat(band3190.meanAnchor()).isCloseTo(0.49833333333333335, within(1e-9));
        var band91365 = gapSegment(report, "91-365");
        assertThat(band91365.sampleCount()).isEqualTo(5);
        assertThat(band91365.suppressed()).isFalse();
        assertThat(band91365.brier()).isCloseTo(0.19993899999999998, within(1e-9));
        assertThat(band91365.meanAnchor()).isCloseTo(0.5980000000000001, within(1e-9));
        var band366 = gapSegment(report, "366+");
        assertThat(band366.sampleCount()).isEqualTo(5);
        assertThat(band366.suppressed()).isFalse();
        assertThat(band366.ece()).isCloseTo(0.19400000000000006, within(1e-9));
        assertThat(band366.meanAnchor()).isCloseTo(0.7, within(1e-9));
        var unknown = gapSegment(report, "UNKNOWN");
        assertThat(unknown.sampleCount()).isEqualTo(1);
        assertThat(unknown.learnerCount()).isEqualTo(1);
        assertThat(unknown.suppressed()).isTrue();
        assertThat(unknown.meanAnchor()).isNull();
        assertThat(report.gapSegments().stream()
                .mapToLong(LearnerModelCalibrationService.GapSegment::sampleCount).sum())
                .isEqualTo(30);
    }
}
