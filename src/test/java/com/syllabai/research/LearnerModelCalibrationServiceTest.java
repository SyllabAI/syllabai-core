package com.syllabai.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S2/ADR-033: the calibration instrument's math, pinned by hand. Every
 * BKT_UPDATED row is a probabilistic forecast (priorMastery made BEFORE the
 * attempt was scored) next to an observed outcome (correctness) — so Brier and
 * expected calibration error over ten equal-width mastery bins are the standard
 * instruments. The fixture numbers are computed in the comments, not asserted
 * on faith.
 */
class LearnerModelCalibrationServiceTest {

    private final TelemetryEventRepository telemetry = mock(TelemetryEventRepository.class);
    private final LearnerModelCalibrationService service =
            new LearnerModelCalibrationService(telemetry);

    private static final UUID NODE = UUID.randomUUID();
    private static final UUID OTHER_NODE = UUID.randomUUID();

    private TelemetryEvent bkt(double prior, boolean correct, UUID nodeId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", UUID.randomUUID().toString());
        payload.put("nodeId", nodeId.toString());
        payload.put("priorMastery", prior);
        payload.put("posteriorMastery", Math.min(1.0, prior + 0.1));
        payload.put("correctness", correct);
        return new TelemetryEvent(
                UUID.randomUUID(), TelemetryEvent.Type.BKT_UPDATED, payload, Instant.now());
    }

    /** a malformed variant: the jsonb payload carries a non-numeric prior */
    private TelemetryEvent bktMalformed(Object prior, boolean correct, UUID nodeId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId.toString());
        payload.put("priorMastery", prior);
        payload.put("correctness", correct);
        return new TelemetryEvent(
                UUID.randomUUID(), TelemetryEvent.Type.BKT_UPDATED, payload, Instant.now());
    }

    @Test
    @DisplayName("Brier, ECE and per-band records match the hand-computed fixture")
    void computesBrierEceAndPerBandRecords() {
        // five node-outcome pairs across three bins:
        //  bin [0.1,0.2): 0.15→correct, 0.15→wrong
        //  bin [0.7,0.8): 0.75→correct
        //  bin [0.9,1.0]: 0.95→correct, 0.999→wrong
        when(telemetry.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED)).thenReturn(List.of(
                bkt(0.15, true, NODE),
                bkt(0.15, false, NODE),
                bkt(0.75, true, NODE),
                bkt(0.95, true, NODE),
                bkt(0.999, false, NODE)));

        LearnerModelCalibrationReport report = service.report(null);

        assertThat(report.samples()).isEqualTo(5);
        // Brier = (0.85² + 0.15² + 0.25² + 0.05² + 0.999²) / 5 = 1.808001 / 5
        assertThat(report.brier()).isCloseTo(0.3616002, org.assertj.core.data.Offset.offset(1e-9));
        // ECE = (2/5)|0.5−0.15| + (1/5)|1−0.75| + (2/5)|0.5−0.9745| = 0.14 + 0.05 + 0.1898
        assertThat(report.ece()).isCloseTo(0.3798, org.assertj.core.data.Offset.offset(1e-9));

        assertThat(report.bins()).hasSize(3);   // only NON-EMPTY bins
        LearnerModelCalibrationReport.Bin low = report.bins().get(0);
        assertThat(low.lower()).isEqualTo(0.1);
        assertThat(low.upper()).isEqualTo(0.2);
        assertThat(low.samples()).isEqualTo(2);
        assertThat(low.meanPredicted()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(low.observedAccuracy()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        // meanBrier = (0.85² + 0.15²)/2 = 0.3725, calibrationError = 0.5 − 0.15 = +0.35 (under-predicts)
        assertThat(low.meanBrier()).isCloseTo(0.3725, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(low.calibrationError()).isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-9));

        LearnerModelCalibrationReport.Bin high = report.bins().get(2);
        assertThat(high.lower()).isEqualTo(0.9);
        assertThat(high.meanPredicted()).isCloseTo(0.9745, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(high.observedAccuracy()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        // (0.05² + 0.999²)/2 = 0.5002505, calibrationError = −0.4745 (over-predicts)
        assertThat(high.meanBrier()).isCloseTo(0.5002505, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(high.calibrationError()).isCloseTo(-0.4745, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("the nodeId filter restricts the aggregate to one KG node")
    void nodeIdFilterRestrictsTheAggregate() {
        when(telemetry.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED)).thenReturn(List.of(
                bkt(0.15, true, NODE),
                bkt(0.75, true, OTHER_NODE)));

        LearnerModelCalibrationReport report = service.report(NODE);

        assertThat(report.samples()).isEqualTo(1);
        assertThat(report.bins()).hasSize(1);
        assertThat(report.bins().get(0).meanPredicted()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("malformed rows are skipped honestly, not fatal — the instrument reports what it can read")
    void malformedRowsAreSkipped() {
        TelemetryEvent stringPrior = bktMalformed("0.5", true, NODE);   // non-numeric prior
        TelemetryEvent missingOutcome;
        {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", NODE.toString());
            payload.put("priorMastery", 0.5);
            // no "correctness" key
            missingOutcome = new TelemetryEvent(
                    UUID.randomUUID(), TelemetryEvent.Type.BKT_UPDATED, payload, Instant.now());
        }
        when(telemetry.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED)).thenReturn(List.of(
                stringPrior, missingOutcome, bkt(0.15, true, NODE)));

        LearnerModelCalibrationReport report = service.report(null);

        assertThat(report.samples()).isEqualTo(1);
        assertThat(report.bins()).hasSize(1);
        assertThat(report.bins().get(0).samples()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty stream is honest: zero samples, null scores, no bins")
    void emptyStreamIsHonestZero() {
        when(telemetry.findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type.BKT_UPDATED))
                .thenReturn(List.of());

        LearnerModelCalibrationReport report = service.report(null);

        assertThat(report.samples()).isZero();
        assertThat(report.brier()).isNull();
        assertThat(report.ece()).isNull();
        assertThat(report.bins()).isEmpty();
    }
}
