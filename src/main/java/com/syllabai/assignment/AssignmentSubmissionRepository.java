package com.syllabai.assignment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only hand-in trail. The latest row per (assignment, learner) is the
 *  current hand-in — callers derive it from the newest-first ordering, the
 *  same way the V47/V48 evidence classes resolve "current state". */
public interface AssignmentSubmissionRepository
        extends JpaRepository<AssignmentSubmission, UUID> {

    List<AssignmentSubmission> findByAssignmentIdOrderByOccurredAtDesc(
            UUID assignmentId, Pageable pageable);

    List<AssignmentSubmission> findByLearnerIdOrderByOccurredAtDesc(
            UUID learnerId, Pageable pageable);
}
