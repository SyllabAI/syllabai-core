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
                new LearnerProperties(null, null, null, null));
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
}
