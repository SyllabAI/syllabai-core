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
 * Calibration instrument pins (S2/ADR-033, challenge C2). Every expected number is
 * hand-derived independently of the implementation: per-row mapped prediction
 * latent·(1−slip) + (1−latent)·guess (slip 0.1; guess 0.01 STRUCTURED / 0.25
 * MCQ-4 / 0.25 untyped), bins on the latent forecast, Brier on the mapped
 * prediction, ECE = Σ (count/n)·|meanPredicted − observed|.
 *
 * The fixture deliberately demonstrates the C2 point: bin 8's latent forecast is
 * 0.80 but its mapped prediction is 0.722 — reporting the raw latent against
 * observed accuracy would read a −0.278 "error" that is really just the emission
 * mapping.
 */
class LearnerModelCalibrationServiceTest {

    private static final UUID NODE_A = UUID.randomUUID();
    private static final UUID NODE_OTHER = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-09-03T12:00:00Z");

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
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodeId", nodeId.toString());
        if (decayedPrior != null) {
            payload.put("decayedPrior", decayedPrior);
        }
        payload.put("correctness", correctness);
        payload.put("questionType", questionType);
        payload.put("optionCount", optionCount);
        return new TelemetryEvent(UUID.randomUUID(),
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
    @DisplayName("report over the full stream: Brier/ECE on the EMISSION-mapped prediction, both filters empty")
    void fullStreamReport() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isEqualTo(7);
        assertThat(report.skippedRows()).isEqualTo(3);
        assertThat(report.brier()).isCloseTo(0.2141624, within(1e-9));
        assertThat(report.ece()).isCloseTo(0.18294285714285716, within(1e-9));

        // bin [0.3,0.4): two MCQ rows — latent 0.335, mapped 0.46775, observed 0.5
        LearnerModelCalibrationService.Bin bin3 = report.bins().get(3);
        assertThat(bin3.count()).isEqualTo(2);
        assertThat(bin3.meanLatentPredicted()).isCloseTo(0.335, within(1e-9));
        assertThat(bin3.meanPredicted()).isCloseTo(0.46775, within(1e-9));
        assertThat(bin3.observedAccuracy()).isCloseTo(0.5, within(1e-9));
        assertThat(bin3.meanBrier()).isCloseTo(0.260885125, within(1e-9));
        assertThat(bin3.calibrationError()).isCloseTo(-0.03225, within(1e-9));

        // bin [0.5,0.6): the untyped row — mapped through the paper guess
        LearnerModelCalibrationService.Bin bin5 = report.bins().get(5);
        assertThat(bin5.count()).isEqualTo(1);
        assertThat(bin5.meanPredicted()).isCloseTo(0.575, within(1e-9));
        assertThat(bin5.calibrationError()).isCloseTo(-0.425, within(1e-9));

        // bin [0.8,0.9): THE C2 demonstration — latent 0.80, mapped 0.722; the raw
        // latent against observed would read a −0.278 error that is pure emission mapping
        LearnerModelCalibrationService.Bin bin8 = report.bins().get(8);
        assertThat(bin8.count()).isEqualTo(1);
        assertThat(bin8.meanLatentPredicted()).isCloseTo(0.8, within(1e-9));
        assertThat(bin8.meanPredicted()).isCloseTo(0.7220000000000001, within(1e-9));
        assertThat(bin8.observedAccuracy()).isCloseTo(1.0, within(1e-9));
        assertThat(bin8.calibrationError()).isCloseTo(-0.2779999999999999, within(1e-9));

        // bin [0.9,1.0]: three structured rows, two correct — over-predicting even mapped
        LearnerModelCalibrationService.Bin bin9 = report.bins().get(9);
        assertThat(bin9.count()).isEqualTo(3);
        assertThat(bin9.meanLatentPredicted()).isCloseTo(0.93, within(1e-9));
        assertThat(bin9.meanPredicted()).isCloseTo(0.8377, within(1e-9));
        assertThat(bin9.observedAccuracy()).isCloseTo(2.0 / 3, within(1e-9));
        assertThat(bin9.meanBrier()).isCloseTo(0.23981918333333338, within(1e-9));
        assertThat(bin9.calibrationError()).isCloseTo(0.17103333333333337, within(1e-9));

        // all ten bins are present, empty ones at zero — traffic is part of the report
        assertThat(report.bins()).hasSize(10);
        assertThat(report.bins().get(0).count()).isZero();
        assertThat(report.bins().get(0).meanPredicted()).isZero();
    }

    @Test
    @DisplayName("nodeId filter excludes other nodes without counting them as malformed")
    void nodeIdFilter() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(NODE_A);

        assertThat(report.sampleCount()).isEqualTo(6);   // NODE_OTHER row filtered out
        assertThat(report.skippedRows()).isEqualTo(3);   // still only the malformed three
        assertThat(report.brier()).isCloseTo(0.2369754666666667, within(1e-9));
        assertThat(report.ece()).isCloseTo(0.16710000000000003, within(1e-9));
        assertThat(report.bins().get(8).count()).isZero();   // the 0.80 row lived on NODE_OTHER
    }

    @Test
    @DisplayName("empty stream: an honest zero report, never NaN")
    void honestEmptyReport() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        assertThat(report.sampleCount()).isZero();
        assertThat(report.skippedRows()).isZero();
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
    @DisplayName("the format cut: segments partition the contract rows and recompute "
            + "per-format stats on the same emission mapping")
    void formatSegmentsPartitionAndRecompute() {
        when(repository.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(fullStream());

        LearnerModelCalibrationService.CalibrationReport report = service.report(null);

        // fixed taxonomy order, empty segments included — diffable across time
        assertThat(report.segments())
                .extracting(LearnerModelCalibrationService.FormatSegment::segment)
                .containsExactly("MCQ_SINGLE(2-3)", "MCQ_SINGLE(4)", "MCQ_SINGLE(5+)",
                        "MCQ_SINGLE(malformed)", "SHORT_ANSWER", "STRUCTURED", "UNTYPED");

        // partition property: the pooled sampleCount is the sum of the segment counts
        assertThat(report.segments().stream()
                .mapToLong(LearnerModelCalibrationService.FormatSegment::sampleCount).sum())
                .isEqualTo(report.sampleCount());

        // STRUCTURED (4 rows: 0.95/0.93/0.91 on NODE_A + 0.80 on NODE_OTHER), hand-derived:
        // mapped 0.8555 / 0.8377 / 0.8199 / 0.722; brier mean
        // (0.02088025+0.02634129+0.67223601+0.077284)/4; bins 9 (n=3, observed 2/3) and 8
        // (n=1, observed 1) → ECE = 3/4·|0.8377−2/3| + 1/4·|0.722−1| = 0.197775
        var structured = segment(report, "STRUCTURED");
        assertThat(structured.sampleCount()).isEqualTo(4);
        assertThat(structured.brier()).isCloseTo(0.1991853875, within(1e-9));
        assertThat(structured.ece()).isCloseTo(0.197775, within(1e-9));

        // MCQ_SINGLE(4) (2 rows), hand-derived: mapped 0.4775 / 0.458, both latent in
        // [0.3,0.4) → single bin, ECE = |0.46775 − 0.5| = 0.03225
        var mcq4 = segment(report, "MCQ_SINGLE(4)");
        assertThat(mcq4.sampleCount()).isEqualTo(2);
        assertThat(mcq4.brier()).isCloseTo(0.260885125, within(1e-9));
        assertThat(mcq4.ece()).isCloseTo(0.03225, within(1e-9));
        assertThat(mcq4.bins().get(3).count()).isEqualTo(2);
        assertThat(mcq4.bins().get(3).meanPredicted()).isCloseTo(0.46775, within(1e-9));

        // UNTYPED (the paper-path row): mapped 0.575, whole segment is bin 5
        var untyped = segment(report, "UNTYPED");
        assertThat(untyped.sampleCount()).isEqualTo(1);
        assertThat(untyped.brier()).isCloseTo(0.180625, within(1e-9));
        assertThat(untyped.ece()).isCloseTo(0.425, within(1e-9));
        assertThat(untyped.bins().get(5).calibrationError()).isCloseTo(-0.425, within(1e-9));

        // empty segments render honest zeros — the protocol's coverage rule keys on these
        var shortAnswer = segment(report, "SHORT_ANSWER");
        assertThat(shortAnswer.sampleCount()).isZero();
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
}
