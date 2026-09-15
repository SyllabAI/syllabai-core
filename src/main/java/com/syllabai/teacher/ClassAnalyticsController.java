package com.syllabai.teacher;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher class intelligence surface (productization sprint 2 §2–§5).
 * Route security: /api/v1/teacher/** requires TEACHER or ADMIN
 * (SecurityConfig) — student and anonymous access is rejected before the
 * controller runs (regression-proven by TeacherRouteSecurityIT).
 *
 * <p>All three endpoints are read-only aggregations over the existing
 * learner-model tables, scoped by {@code rootId} (the subject's knowledge
 * root — the same scoping parameter every learner-facing surface uses, so
 * class analytics can never aggregate across subjects). The drill-down path
 * is: class overview → topic (heatmap cell) → affected learners →
 * representative evidence → validated questions / Test Builder.</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/class")
public class ClassAnalyticsController {

    private final ClassAnalyticsService analytics;

    public ClassAnalyticsController(ClassAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /**
     * Class overview (§2): cohort size, evidence reach, coverage, the
     * topic × class heatmap aggregates, weak prerequisite areas and recent
     * activity. Unmeasured topics carry null means and UNMEASURED bands —
     * nothing is fabricated for missing evidence.
     */
    @GetMapping("/overview")
    public ClassAnalyticsService.ClassOverviewView overview(@RequestParam UUID rootId) {
        return analytics.overview(rootId);
    }

    /**
     * Learner list (§2): one evidence-separated row per enabled learner.
     * Learners with no evidence in this subject read evidenceState
     * UNMEASURED with null mastery — honestly unmeasured, never zero.
     */
    @GetMapping("/learners")
    public List<ClassAnalyticsService.ClassLearnerView> learners(@RequestParam UUID rootId) {
        return analytics.learners(rootId);
    }

    /**
     * Topic drill-down (§5): class → topic → learners → evidence →
     * intervention. A topic outside the subject's subtree is a 404 (subject
     * isolation), never a silent cross-subject hop.
     */
    @GetMapping("/topics/{nodeId}/drill-down")
    public ClassAnalyticsService.TopicDrillDownView drillDown(
            @PathVariable UUID nodeId, @RequestParam UUID rootId) {
        return analytics.topicDrillDown(rootId, nodeId);
    }
}
