package com.syllabai.research;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S2/ADR-033 — the calibration instrument's read surface: predicted-vs-observed
 * for the BKT mastery model, aggregated from the BKT_UPDATED telemetry stream
 * (Brier score, expected calibration error, per-band records). This is the
 * measurement Master Spec §BKT's "monthly parameter recalibration" promise
 * needs — it reports the model's honesty, it never mutates anything.
 *
 * <p>Operator/research surface: aggregate model-quality statistics only, but
 * they span ALL learners, so the route is TEACHER/ADMIN-gated — mirrored by
 * the {@code /api/v1/research/**} matcher in SecurityConfig (defense in depth,
 * same pattern as the teacher analytics controller).</p>
 */
@RestController
@RequestMapping("/api/v1/research")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class CalibrationController {

    private final LearnerModelCalibrationService calibration;

    public CalibrationController(LearnerModelCalibrationService calibration) {
        this.calibration = calibration;
    }

    /** @param nodeId optional — restrict the aggregate to one KG node */
    @GetMapping("/learner-model/calibration")
    public LearnerModelCalibrationReport calibration(
            @RequestParam(name = "nodeId", required = false) UUID nodeId) {
        return calibration.report(nodeId);
    }
}
