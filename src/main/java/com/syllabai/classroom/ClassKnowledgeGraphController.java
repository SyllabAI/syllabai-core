package com.syllabai.classroom;

import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassKnowledgeGraphView;
import com.syllabai.classroom.dto.ClassKnowledgeGraphViews.ClassNodeStudentsView;
import com.syllabai.learner.dto.LearnerKnowledgeGraphView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The F-072 class knowledge-graph heatmap surface: one read endpoint that
 * fuses the curriculum subtree with class-level learner aggregation (§13.3)
 * and the teaching-coverage overlay (§13.4). Route security: /api/v1/teacher/**
 * requires TEACHER or ADMIN (SecurityConfig) plus this class-level check
 * (defense in depth, the TeacherClassController pattern) AND per-object
 * ownership — a teacher reads the heatmap only of classes they own (§17:
 * backend authorization is mandatory, the UI hiding data is not).
 *
 * <p>Gates in order: class exists (404) and is this teacher's (403), then
 * the root node must exist (404, thrown by the graph read service). Unlike
 * the coverage WRITE routes there is deliberately NO archived-class gate: a
 * teacher may inspect a past class's heatmap — 409 is a write gate, and this
 * endpoint is a pure read model that touches nothing.</p>
 *
 * <p>THE INDEPENDENT-STUDENT RULE: the aggregation roster is exactly this
 * class's enabled member rows — a student without a membership row in this
 * class never appears in any aggregated number (pinned by the flow IT).</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/classes/{classId}/knowledge-graph")
// deep-audit 09-28 M5: method-level role check mirroring the route rule
// in SecurityConfig (defense in depth — the route matchers stay authoritative)
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class ClassKnowledgeGraphController {

    private final SchoolClassRepository classes;
    private final ClassKnowledgeGraphService heatmaps;

    public ClassKnowledgeGraphController(SchoolClassRepository classes,
                                         ClassKnowledgeGraphService heatmaps) {
        this.classes = classes;
        this.heatmaps = heatmaps;
    }

    /**
     * The class KG heatmap for one subject root — curriculum topology +
     * per-node class understanding distribution + coverage overlay, in
     * curriculum order. Unmeasured nodes carry honest nulls; unrecorded
     * coverage is honest absence (grey means absent teaching coverage,
     * never low understanding).
     */
    @GetMapping
    public ClassKnowledgeGraphView graph(@CurrentUserId UUID teacherId,
                                         @PathVariable UUID classId,
                                         @RequestParam UUID rootId) {
        SchoolClass clazz = ownedClass(teacherId, classId);
        return heatmaps.graph(clazz, rootId);
    }

    /**
     * TFA-07 drill-down, leg "weak node → affected students" (§13.5): one
     * node's per-student detail for this class — the heatmap's roster at
     * student grain (raw + effective mastery, the shared band vocabulary),
     * misconception estimates and bounded recent attempts per student, with
     * the §13.3 distribution restated so the panel can never disagree with
     * the graph it opened from. Same gates as the heatmap (404 unknown
     * class/root, 403 another teacher's class, node-outside-subject 404,
     * archived classes readable) and the same independent-student rule —
     * only this class's enabled members can appear.
     */
    @GetMapping("/nodes/{nodeId}/students")
    public ClassNodeStudentsView nodeStudents(@CurrentUserId UUID teacherId,
                                              @PathVariable UUID classId,
                                              @PathVariable UUID nodeId,
                                              @RequestParam UUID rootId) {
        SchoolClass clazz = ownedClass(teacherId, classId);
        return heatmaps.nodeStudents(clazz, rootId, nodeId);
    }

    /**
     * TFA-07 drill-down, leg "affected students → individual student graph"
     * (§14): ONE student's subject graph through the SAME F-034 read model
     * the student themselves sees — the teacher lens adds only gates, never
     * a second graph implementation. §17 privacy boundary: the learner must
     * be an ENABLED member of THIS class (404 otherwise); backend-enforced,
     * the UI hiding data is not authorization.
     */
    @GetMapping("/learners/{learnerId}/knowledge-graph")
    public LearnerKnowledgeGraphView learnerKnowledgeGraph(@CurrentUserId UUID teacherId,
                                                           @PathVariable UUID classId,
                                                           @PathVariable UUID learnerId,
                                                           @RequestParam UUID rootId) {
        SchoolClass clazz = ownedClass(teacherId, classId);
        return heatmaps.learnerKnowledgeGraph(clazz, learnerId, rootId);
    }

    /** the §17 gate: the class must exist AND be owned by this teacher */
    private SchoolClass ownedClass(UUID teacherId, UUID classId) {
        SchoolClass c = classes.findById(classId)
                .orElseThrow(() -> new NotFoundException("class not found"));
        if (!c.teacherId().equals(teacherId)) {
            throw new ForbiddenException("this class belongs to another teacher");
        }
        return c;
    }
}
