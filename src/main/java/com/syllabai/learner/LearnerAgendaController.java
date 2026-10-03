package com.syllabai.learner;

import com.syllabai.assignment.Assignment;
import com.syllabai.assignment.AssignmentRepository;
import com.syllabai.assignment.AssignmentSubmission;
import com.syllabai.assignment.AssignmentSubmissionRepository;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentSubmissionView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.assignment.dto.AssignmentViews.LearnerAssignmentView;
import com.syllabai.classroom.ClassMemberRepository;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.learner.dto.AgendaView;
import com.syllabai.learner.dto.LearnerStateView.ReviewView;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner agenda (T-C76, Master Spec §22: GET /api/v1/learners/me/agenda) —
 * the executive read model: one call composes what is on this learner's
 * plate. Read-only by construction (the CourseStats precedent): it consumes
 * the same verified read services as the learner-state, assignments and
 * recommendations surfaces — no client-side joins, no new persistence, no
 * parallel tracking model, and nothing the chat LLM could fabricate: every
 * row here is a durable evidence row served through its owning module.
 *
 * <p>Composition (all three blocks are this learner's own rows):</p>
 * <ol>
 *   <li>dueReviews — the forgetting-curve's PENDING schedule (V18), due
 *       soonest first, node titles resolved in one batched read (the P1
 *       pilot-UX rule — clients never render raw UUIDs);</li>
 *   <li>assignments — the V49/V51 visible work with the learner's latest
 *       append-only hand-in beside each (null until they submit), re-ordered
 *       due-soonest-first (undated last, then newest-created): an agenda
 *       answers "what is due", not "what was just set"; the 50-row bound
 *       matches the teacher/learner list endpoints;</li>
 *   <li>actions — the ADR-017 next-best-action block for the requested
 *       subject {@code rootId} (the same deterministic engine as
 *       GET /learners/me/recommendations, advice-not-facts posture
 *       unchanged), or {@code null} when no root is supplied.</li>
 * </ol>
 *
 * <p>Deliberately absent: any derived "overdue"/"done" flags (clients derive
 * them from {@code dueAt} + {@code mySubmission} — the server does not grow
 * a second vocabulary for the same facts), streaks, notifications and plan
 * persistence (the ADR-035 executive-layer proposal is operator-gated and
 * none of it is pre-built here).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class LearnerAgendaController {

    /** assignment row bound — matches the teacher and learner lists */
    private static final int ASSIGNMENT_LIMIT = 50;

    private final ReviewScheduleRepository reviewSchedules;
    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final ClassMemberRepository classMembers;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final NextBestActionService nextBestActions;

    public LearnerAgendaController(ReviewScheduleRepository reviewSchedules,
                                   AssignmentRepository assignments,
                                   AssignmentSubmissionRepository submissions,
                                   ClassMemberRepository classMembers,
                                   KnowledgeNodeRepository knowledgeNodes,
                                   NextBestActionService nextBestActions) {
        this.reviewSchedules = reviewSchedules;
        this.assignments = assignments;
        this.submissions = submissions;
        this.classMembers = classMembers;
        this.knowledgeNodes = knowledgeNodes;
        this.nextBestActions = nextBestActions;
    }

    @GetMapping("/agenda")
    public AgendaView agenda(@CurrentUserId UUID learnerId,
                             @RequestParam(required = false) UUID rootId) {
        Instant now = Instant.now();

        // 1) due spaced reviews — same query as the learner-state read model
        List<ReviewSchedule> reviews = reviewSchedules
                .findByLearnerIdAndStatusOrderByDueAtAsc(learnerId, ReviewSchedule.Status.PENDING);
        Set<UUID> reviewNodeIds = new HashSet<>();
        reviews.forEach(r -> reviewNodeIds.add(r.nodeId()));
        Map<UUID, String> titles = reviewNodeIds.isEmpty() ? Map.of()
                : knowledgeNodes.findAllById(reviewNodeIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                KnowledgeNode::id, KnowledgeNode::title, (a, b) -> a));
        List<ReviewView> dueReviews = reviews.stream()
                .map(r -> new ReviewView(r.nodeId(), r.dueAt(), r.reason().name(),
                        titles.get(r.nodeId())))
                .toList();

        // 2) assignments — the V51 visibility filter is THE authorization
        //    (identical query + filter to LearnerAssignmentController.list),
        //    re-ordered for the agenda question: due soonest first, undated
        //    last, then newest-created first
        Map<UUID, AssignmentSubmission> mine = new HashMap<>();
        for (AssignmentSubmission s : submissions
                .findByLearnerIdOrderByOccurredAtDesc(learnerId, PageRequest.of(0, 2000))) {
            mine.putIfAbsent(s.assignmentId(), s);
        }
        List<Assignment> visible = new ArrayList<>(assignments
                .findByOrderByCreatedAtDesc(PageRequest.of(0, ASSIGNMENT_LIMIT)).stream()
                .filter(a -> a.classId() == null
                        || classMembers.existsByClassIdAndStudentId(a.classId(), learnerId))
                .toList());
        visible.sort(Comparator
                .comparing(Assignment::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Assignment::createdAt, Comparator.reverseOrder()));
        List<LearnerAssignmentView> assignmentRows = visible.stream()
                .map(a -> new LearnerAssignmentView(
                        AssignmentView.from(a),
                        mine.containsKey(a.id())
                                ? AssignmentSubmissionView.from(mine.get(a.id()))
                                : null))
                .toList();

        // 3) next best actions — only when the caller asks for a subject root;
        //    the NBA engine owns its own scoping/validation contract (404 on
        //    an unknown root, deterministic ranked advice otherwise)
        NextBestActionsView actions = rootId == null
                ? null : nextBestActions.actionsFor(learnerId, rootId);

        return new AgendaView(learnerId, now, dueReviews, assignmentRows, actions);
    }
}
