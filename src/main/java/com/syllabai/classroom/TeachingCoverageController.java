package com.syllabai.classroom;

import com.syllabai.classroom.dto.TeachingCoverageViews.CoverageEventView;
import com.syllabai.classroom.dto.TeachingCoverageViews.CoverageRowView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teaching coverage — the teacher/class/subject taught-state overlay (V52,
 * TFA-03; TEACHER_ARCHITECTURE §13.4 + §19). Route security: /api/v1/teacher/**
 * requires TEACHER or ADMIN (SecurityConfig) plus this class-level check
 * (defense in depth, the TeacherClassController pattern) AND per-object
 * ownership checks — a teacher marks coverage only on classes they own
 * (§17: backend authorization is mandatory, the UI hiding data is not).
 *
 * <p>THE POINT: the Class KG must distinguish not-yet-taught curriculum from
 * taught-but-weak. Grey graph nodes mean ABSENT TEACHING COVERAGE — never
 * low understanding. NOT_TAUGHT is not a mastery state: this module writes
 * only its own two tables and reads knowledge_nodes; nothing in the learner
 * model (BKT, SkillState, misconception evidence, review schedule) is
 * touched (pinned by the flow IT, the same honesty rule as V51).</p>
 *
 * <p>DESIGN RULES: the spec-point gate is the V39 invariant — applicability
 * is populated ONLY on seed-owned spec-point rows, so any other node is
 * refused fail-closed (no guessing what "counts" as a specification point);
 * GET serves only recorded rows (absence of a row is the honest unrecorded
 * state — the API never fabricates NOT_TAUGHT rows for a whole curriculum);
 * the knowledge_nodes FK carries the V30 NO-ACTION rule so a curriculum
 * refresh cannot silently wipe teacher assertions; course refs stay
 * hub-owned opaque slugs (the V47/V48/V49/V51 ruling — no slug↔code
 * cross-check in core).</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/classes/{classId}/coverage")
// deep-audit 09-28 M5: method-level role check mirroring the route rule
// in SecurityConfig (defense in depth — the route matchers stay authoritative)
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TeachingCoverageController {

    private final TeachingCoverageRepository coverage;
    private final TeachingCoverageEventRepository events;
    private final SchoolClassRepository classes;
    private final KnowledgeNodeRepository knowledgeNodes;

    public TeachingCoverageController(TeachingCoverageRepository coverage,
                                      TeachingCoverageEventRepository events,
                                      SchoolClassRepository classes,
                                      KnowledgeNodeRepository knowledgeNodes) {
        this.coverage = coverage;
        this.events = events;
        this.classes = classes;
        this.knowledgeNodes = knowledgeNodes;
    }

    // ── reads ───────────────────────────────────────────────────────────

    /** the class's recorded coverage rows — only what teachers asserted */
    @GetMapping
    public List<CoverageRowView> list(@CurrentUserId UUID teacherId,
                                      @PathVariable UUID classId) {
        ownedClass(teacherId, classId);
        List<TeachingCoverage> rows = coverage.findByClassIdOrderBySpecPointNodeIdAsc(classId);
        Map<UUID, KnowledgeNode> nodes = loadNodes(
                rows.stream().map(TeachingCoverage::specPointNodeId).toList());
        return rows.stream()
                .map(r -> CoverageRowView.of(r, nodes.get(r.specPointNodeId())))
                .toList();
    }

    /** the per-point audit trail, newest first — served verbatim */
    @GetMapping("/{specPointNodeId}/history")
    public List<CoverageEventView> history(@CurrentUserId UUID teacherId,
                                           @PathVariable UUID classId,
                                           @PathVariable UUID specPointNodeId) {
        ownedClass(teacherId, classId);
        return events
                .findByClassIdAndSpecPointNodeIdOrderByCreatedAtDesc(classId, specPointNodeId)
                .stream()
                .map(CoverageEventView::of)
                .toList();
    }

    // ── marking ─────────────────────────────────────────────────────────

    /**
     * Assert coverage for one specification point. Fail-closed gates in
     * order: class exists (404) and is the teacher's (403), class is ACTIVE
     * (409), node exists (404) and is a spec-point node (400, the V39
     * invariant). An identical re-mark (same status AND same normalized
     * note) is the idempotent no-op it honestly is; any information change
     * appends exactly one audit event and moves the state row in the same
     * transaction.
     */
    @PutMapping("/{specPointNodeId}")
    public CoverageRowView mark(@CurrentUserId UUID teacherId,
                                @PathVariable UUID classId,
                                @PathVariable UUID specPointNodeId,
                                @Valid @RequestBody MarkRequest request) {
        SchoolClass c = ownedClass(teacherId, classId);
        if (c.status() != SchoolClass.Status.ACTIVE) {
            throw new ConflictException("this class is archived — reopen it before marking coverage");
        }
        TeachingCoverage.Status parsed = TeachingCoverage.Status.parse(request.status());
        if (parsed == null) {
            throw new BadRequestException("status must be 'taught' or 'not-taught'");
        }
        KnowledgeNode node = knowledgeNodes.findById(specPointNodeId)
                .orElseThrow(() -> new NotFoundException("specification point not found"));
        if (node.nodeType() != NodeType.SUBTOPIC || node.applicability() == null) {
            // the V39 invariant IS the spec-point gate: applicability is
            // populated only on seed-owned spec-point rows — refuse
            // everything else instead of guessing
            throw new BadRequestException("that node is not a specification point");
        }
        String note = normalizeNote(request.note());

        TeachingCoverage row = coverage
                .findByClassIdAndSpecPointNodeId(classId, specPointNodeId)
                .orElse(null);
        if (row == null) {
            row = coverage.save(new TeachingCoverage(
                    classId, specPointNodeId, parsed, teacherId, note));
            events.save(new TeachingCoverageEvent(
                    classId, specPointNodeId, parsed, null, teacherId, note));
            return CoverageRowView.of(row, node);
        }
        if (row.status() == parsed && java.util.Objects.equals(row.note(), note)) {
            return CoverageRowView.of(row, node); // identical re-mark: honest no-op
        }
        TeachingCoverage.Status previous = row.status();
        row.reassert(parsed, teacherId, note);
        coverage.save(row);
        events.save(new TeachingCoverageEvent(
                classId, specPointNodeId, parsed, previous, teacherId, note));
        return CoverageRowView.of(row, node);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** the §17 gate: the class must exist AND be owned by this teacher */
    private SchoolClass ownedClass(UUID teacherId, UUID classId) {
        SchoolClass c = classes.findById(classId)
                .orElseThrow(() -> new NotFoundException("class not found"));
        if (!c.teacherId().equals(teacherId)) {
            throw new ForbiddenException("this class belongs to another teacher");
        }
        return c;
    }

    private Map<UUID, KnowledgeNode> loadNodes(List<UUID> ids) {
        Map<UUID, KnowledgeNode> out = new HashMap<>();
        for (KnowledgeNode n : knowledgeNodes.findAllById(new ArrayList<>(ids))) {
            out.put(n.id(), n);
        }
        return out;
    }

    private static String normalizeNote(String note) {
        if (note == null) return null;
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ── request bodies ──────────────────────────────────────────────────

    public record MarkRequest(
            @NotBlank String status,
            @Size(max = 500) String note) {
    }
}
