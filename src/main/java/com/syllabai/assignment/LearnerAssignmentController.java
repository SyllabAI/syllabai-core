package com.syllabai.assignment;

import com.syllabai.assignment.dto.AssignmentViews.AssignmentSubmissionView;
import com.syllabai.assignment.dto.AssignmentViews.AssignmentView;
import com.syllabai.assignment.dto.AssignmentViews.LearnerAssignmentView;
import com.syllabai.classroom.ClassMemberRepository;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner assignments (V49): the learner side of the contract — see the
 * work the teacher set and hand it in as append-only evidence.
 *
 * <p>Contract shape: {@code GET /api/v1/learners/me/assignments} lists the
 * newest assignments with MY current hand-in beside each (null until I
 * submit) — the learner's own trail, never another learner's. {@code POST
 * .../{id}/submissions} appends a hand-in: the assignment must exist (404)
 * and be open (closed = 409), and the payload is validated against the
 * assignment's own bounds (a hand-in can never claim more questions than
 * the assignment has, nor a score above its marks total). A re-hand-in is
 * NEW evidence — the latest row per assignment is the current state, the
 * trail preserves the history (the V47/V48 append-only discipline).</p>
 *
 * <p>Evidence class semantics: a hand-in is completion state, NEVER mastery
 * — no BKT / SkillState / misconception / review writes (pinned by
 * AssignmentFlowIT). The mastery evidence practice generates flows through
 * the existing attempt pipeline unchanged. Deliberately NOT in the R8 LLM
 * tier — authenticated writes with zero LLM cost (same ruling as V47/V48).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/assignments")
public class LearnerAssignmentController {

    /** newest-first list bound — matches the teacher list */
    private static final int LIST_LIMIT = 50;

    private final AssignmentRepository assignments;
    private final AssignmentSubmissionRepository submissions;
    private final ClassMemberRepository classMembers;

    public LearnerAssignmentController(AssignmentRepository assignments,
                                       AssignmentSubmissionRepository submissions,
                                       ClassMemberRepository classMembers) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.classMembers = classMembers;
    }

    @GetMapping
    public List<LearnerAssignmentView> list(@CurrentUserId UUID learnerId) {
        // my trail, latest first — first row per assignment is my current hand-in
        Map<UUID, AssignmentSubmission> mine = new HashMap<>();
        for (AssignmentSubmission s : submissions
                .findByLearnerIdOrderByOccurredAtDesc(learnerId, PageRequest.of(0, 2000))) {
            mine.putIfAbsent(s.assignmentId(), s);
        }
        // V51 visibility: NULL class target = every enabled student (the V49
        // default — independent students included); class target = members only
        // (the independent-student rule — no membership row, no classroom work).
        return assignments.findByOrderByCreatedAtDesc(PageRequest.of(0, LIST_LIMIT)).stream()
                .filter(a -> a.classId() == null
                        || classMembers.existsByClassIdAndStudentId(a.classId(), learnerId))
                .map(a -> new LearnerAssignmentView(
                        AssignmentView.from(a),
                        mine.containsKey(a.id())
                                ? AssignmentSubmissionView.from(mine.get(a.id()))
                                : null))
                .toList();
    }

    @PostMapping("/{id}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentSubmissionView submit(@CurrentUserId UUID learnerId,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody SubmissionRequest request) {
        Assignment assignment = assignments.findById(id)
                .orElseThrow(() -> new NotFoundException("unknown assignment: " + id));
        // V51 hand-in gate: a class-targeted assignment accepts hand-ins from
        // its members only — the membership rows are the authorization, not
        // the hub's UI chrome (TEACHER_ARCHITECTURE §17)
        if (assignment.classId() != null
                && !classMembers.existsByClassIdAndStudentId(assignment.classId(), learnerId)) {
            throw new ForbiddenException("this assignment targets a class you are not in");
        }
        if (assignment.status() == Assignment.Status.CLOSED) {
            throw new ConflictException("assignment is closed: " + assignment.title());
        }
        if (request.questionsCompleted() > assignment.questionCount()) {
            throw new BadRequestException("questionsCompleted " + request.questionsCompleted()
                    + " exceeds the assignment's " + assignment.questionCount() + " questions");
        }
        if (request.score() != null && request.score() > assignment.marksTotal()) {
            throw new BadRequestException("score " + request.score()
                    + " exceeds the assignment's " + assignment.marksTotal() + " marks");
        }
        AssignmentSubmission saved = submissions.save(new AssignmentSubmission(
                id, learnerId, request.questionsCompleted(), request.score(), null));
        return AssignmentSubmissionView.from(saved);
    }

    public record SubmissionRequest(
            @NotNull @Min(0) Integer questionsCompleted,
            @Min(0) @Max(1000) Integer score) {
    }
}
