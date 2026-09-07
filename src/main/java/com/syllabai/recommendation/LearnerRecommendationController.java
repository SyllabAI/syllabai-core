package com.syllabai.recommendation;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.recommendation.dto.NextBestActionsView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing next-best-action endpoint (Master Spec §22 route table:
 * GET /api/v1/learners/me/recommendations). Subject-scoped by {@code rootId}
 * (a subject's KG root, resolvable via GET /api/v1/curriculum/subjects), the
 * same scoping convention as GET /api/v1/learners/me/knowledge-graph.
 * Read-only; authenticated (any role) like the other /learners/me surfaces.
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class LearnerRecommendationController {

    private final NextBestActionService nextBestActions;

    public LearnerRecommendationController(NextBestActionService nextBestActions) {
        this.nextBestActions = nextBestActions;
    }

    @GetMapping("/recommendations")
    public NextBestActionsView recommendations(@CurrentUserId UUID learnerId,
                                               @RequestParam UUID rootId) {
        return nextBestActions.actionsFor(learnerId, rootId);
    }
}
