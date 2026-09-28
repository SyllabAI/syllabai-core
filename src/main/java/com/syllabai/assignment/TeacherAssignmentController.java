package com.syllabai.assignment;

import com.syllabai.assignment.dto.AssignmentViews.AssignmentRosterRow;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentRosterView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentSummaryView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.identity.Role;
import com.syllabai.identity.UserRepository;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher assignments (V49 — the "assignments → core contract" tranche,
 * ADR-029 4.10): create from the hub's assembled question set, list with
 * REAL completion stats, inspect the roster, and drive the open/closed
 * lifecycle. Route security: /api/v1/teacher/** requires TEACHER or ADMIN
 * (SecurityConfig) plus this class-level check (deep-audit M5 defense in
 * depth).
 *
 * <p>FAIL-CLOSED TARGETS (the V47/V48 attribution discipline applied to
 * teacher-authored content): every spec ref must resolve against the
 * ingested curriculum knowledge graph to a CURRICULUM-STRUCTURE node below
 * the subject root (UNIT/TOPIC/SUBTOPIC). Unknown codes, the subject root
 * and the semantic layer (CONCEPT/MISCONCEPTION) are 404 and nothing is
 * written — an assignment can never target work the curriculum does not
 * have.</p>
 *
 * <p>The cohort is the enabled STUDENT set (the TeacherRosterController
 * ruling — no class entity in the pilot): missing = an enabled student
 * without a hand-in row, COMPUTED at read time so it can never drift from
 * identity truth. Hand-ins themselves are append-only evidence rows; the
 * latest row per learner is the current state.</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/assignments")
// deep-audit 09-28 M5: method-level role check mirroring the route rule
// in SecurityConfig (defense in depth — the route matchers stay authoritative)
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TeacherAssignmentController {

    /** newest-first list bound — assignments are teacher-authored and few */
    private static final int LIST_LIMIT = 50;

    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final UserRepository users;

    public TeacherAssignmentController(AssignmentRepository assignments,
                                       AssignmentSubmissionRepository submissions,
                                       KnowledgeNodeRepository knowledgeNodes,
                                       UserRepository users) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.knowledgeNodes = knowledgeNodes;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentView create(@CurrentUserId UUID teacherId,
                                 @Valid @RequestBody CreateRequest request) {
        List<String> refs = new ArrayList<>(new LinkedHashSet<>(request.specRefs()));
        for (String code : refs) {
            resolveTarget(code);
        }
        Assignment saved = assignments.save(new Assignment(
                teacherId,
                request.courseSlug(),
                request.courseLabel(),
                request.title().trim(),
                refs,
                request.marksTotal(),
                request.questionCount(),
                parseDueAt(request.dueAt()),
                Assignment.Status.OPEN));
        return AssignmentView.from(saved);
    }

    /** newest-first list with real completion stats (computed, never stored) */
    @GetMapping
    public List<AssignmentSummaryView> list() {
        int cohort = users.findEnabledByRole(Role.STUDENT).size();
        return assignments.findByOrderByCreatedAtDesc(PageRequest.of(0, LIST_LIMIT)).stream()
                .map(a -> toSummary(a, latestByLearner(a.id()), cohort))
                .toList();
    }

    /** the full roster for one assignment: every enabled student, real state */
    @GetMapping("/{id}")
    public AssignmentRosterView roster(@PathVariable UUID id) {
        Assignment assignment = assignments.findById(id)
                .orElseThrow(() -> new NotFoundException("unknown assignment: " + id));
        Map<UUID, AssignmentSubmission> latest = latestByLearner(id);
        List<AssignmentRosterRow> rows = users.findEnabledByRole(Role.STUDENT).stream()
                .map(u -> {
                    AssignmentSubmission s = latest.get(u.id());
                    if (s == null) {
                        return new AssignmentRosterRow(u.id(), u.displayName(),
                                "missing", null, null, null);
                    }
                    boolean late = s.occurredAt().isAfter(assignment.dueAt());
                    return new AssignmentRosterRow(u.id(), u.displayName(),
                            late ? "late" : "complete", s.occurredAt(), s.score(),
                            s.questionsCompleted());
                })
                // submitted first (newest hand-in first), then missing by name
                .sorted(Comparator
                        .comparing((AssignmentRosterRow r) -> "missing".equals(r.state()))
                        .thenComparing(r -> r.submittedAt() == null ? Instant.EPOCH : r.submittedAt(),
                                Comparator.reverseOrder())
                        .thenComparing(AssignmentRosterRow::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new AssignmentRosterView(AssignmentView.from(assignment), rows);
    }

    /** lifecycle: close (no new hand-ins) or reopen */
    @PostMapping("/{id}/status")
    public AssignmentView setStatus(@PathVariable UUID id,
                                    @Valid @RequestBody StatusRequest request) {
        Assignment.Status status = Assignment.Status.parse(request.status());
        if (status == null) {
            throw new BadRequestException(
                    "status must be \"open\" or \"closed\": " + request.status());
        }
        Assignment assignment = assignments.findById(id)
                .orElseThrow(() -> new NotFoundException("unknown assignment: " + id));
        assignment.status(status);
        return AssignmentView.from(assignments.save(assignment));
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** fail-closed structural gate — the same ruling as V47 ratings / V48 votes */
    private void resolveTarget(String code) {
        KnowledgeNode node = knowledgeNodes.findByCode(code)
                .orElseThrow(() -> new NotFoundException("unknown assignment target: " + code));
        boolean structureNode = node.nodeType() == NodeType.UNIT
                || node.nodeType() == NodeType.TOPIC
                || node.nodeType() == NodeType.SUBTOPIC;
        if (!structureNode) {
            throw new NotFoundException(
                    "not an assignment target (needs a curriculum-structure node below the subject root): "
                            + code);
        }
    }

    private Instant parseDueAt(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BadRequestException("dueAt must be an ISO-8601 instant: " + raw);
        }
    }

    /** latest hand-in per learner (the append-only trail's current state) */
    private Map<UUID, AssignmentSubmission> latestByLearner(UUID assignmentId) {
        Map<UUID, AssignmentSubmission> latest = new HashMap<>();
        for (AssignmentSubmission s : submissions
                .findByAssignmentIdOrderByOccurredAtDesc(assignmentId, PageRequest.of(0, 2000))) {
            latest.putIfAbsent(s.learnerId(), s); // newest-first: first row wins
        }
        return latest;
    }

    private AssignmentSummaryView toSummary(Assignment a,
                                            Map<UUID, AssignmentSubmission> latest,
                                            int cohort) {
        int submitted = latest.size();
        long late = latest.values().stream()
                .filter(s -> s.occurredAt().isAfter(a.dueAt()))
                .count();
        List<Integer> scores = latest.values().stream()
                .map(AssignmentSubmission::score)
                .filter(s -> s != null)
                .toList();
        Double meanScore = scores.isEmpty() ? null
                : scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new AssignmentSummaryView(AssignmentView.from(a), submitted, (int) late,
                Math.max(0, cohort - submitted), meanScore);
    }

    // ── request records ────────────────────────────────────────────────────

    public record CreateRequest(
            @NotBlank @Size(min = 1, max = 200) String title,
            @NotBlank @Size(min = 1, max = 64) @Pattern(regexp = "^[a-z0-9-]+$",
                    message = "course slug must be the hub course slug") String courseSlug,
            @NotBlank @Size(min = 1, max = 120) String courseLabel,
            @NotNull @Size(min = 1, max = 60) List<@NotBlank @Size(min = 2, max = 40)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "spec refs are curriculum codes") String> specRefs,
            @NotNull @Min(1) @Max(1000) Integer marksTotal,
            @NotNull @Min(1) @Max(200) Integer questionCount,
            @NotBlank String dueAt) {
    }

    public record StatusRequest(@NotBlank String status) {
    }
}
