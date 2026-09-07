package com.syllabai.assessment;

import com.syllabai.assessment.dto.AttemptHistoryView;
import com.syllabai.identity.CurrentUserId;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing attempt-history endpoint — the Review Hub minimal slice
 * (charter §14). Route follows the /api/v1/learners/me/* convention
 * (Master Spec §22): the resource is owned by the authenticated learner;
 * the identity comes from the JWT, never from a request parameter.
 *
 * <p>Read-only; authenticated (any role). Served by the assessment module
 * because it owns the attempts/answers evidence rows — the route prefix
 * does not imply module ownership (same pattern as
 * {@code LearnerRecommendationController}).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class AttemptHistoryController {

    private final AttemptHistoryService history;

    public AttemptHistoryController(AttemptHistoryService history) {
        this.history = history;
    }

    /**
     * Most-recent-first attempt history, capped (default 50, max 100 —
     * {@code ?limit=} is advisory, never a pagination protocol).
     */
    @GetMapping("/attempts")
    public AttemptHistoryView attempts(@CurrentUserId UUID learnerId,
                                       @RequestParam(required = false) Integer limit) {
        return history.historyFor(learnerId, limit);
    }
}
