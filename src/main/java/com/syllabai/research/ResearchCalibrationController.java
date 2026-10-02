package com.syllabai.research;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The research read surface (S2/ADR-033): predicted-vs-observed calibration of the
 * learner model over the BKT_UPDATED telemetry stream. Aggregates span ALL learners
 * and are never student-visible; the route is gated TEACHER/ADMIN in SecurityConfig
 * and re-gated here at method level (defense in depth, matching the teacher
 * surfaces' posture). The small-cell question (ADR-033 challenge C7's three-way
 * choice: ADMIN-only, class-scoped, or k-anonymity) was DECIDED 2026-10-02 for
 * k-anonymity enforced by the API: every cell with fewer than 5 distinct learners
 * renders its statistics null ({@code suppressed: true}) while all counts stay
 * visible — the unit is the learner, not the row, because one marked attempt
 * updates every node it honestly tests. The report carries counts, never learner
 * identifiers.
 */
@RestController
@RequestMapping("/api/v1/research/learner-model")
public class ResearchCalibrationController {

    private final LearnerModelCalibrationService calibration;

    public ResearchCalibrationController(LearnerModelCalibrationService calibration) {
        this.calibration = calibration;
    }

    /**
     * The calibration report: Brier, ECE and ten equal-width predicted bins over the
     * BKT_UPDATED stream (see {@link LearnerModelCalibrationService} for what is
     * predicted, what is observed, and the emission mapping), plus the per-format
     * segments (C4 protocol §2's mandatory format axis — the pooled sampleCount is
     * always the sum of the segment counts). Citing rules live in the master pack's
     * CALIBRATION_REVIEW_PROTOCOL.md; the wire format carries everything.
     *
     * @param nodeId optional knowledge-graph node filter — one node's calibration curve
     */
    @GetMapping("/calibration")
    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    public LearnerModelCalibrationService.CalibrationReport calibration(
            @RequestParam(required = false) UUID nodeId) {
        return calibration.report(nodeId);
    }
}
