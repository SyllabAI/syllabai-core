package com.syllabai.learner;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewScheduleRepository extends JpaRepository<ReviewSchedule, UUID> {

    List<ReviewSchedule> findByLearnerIdAndStatusOrderByDueAtAsc(UUID learnerId, ReviewSchedule.Status status);

    long countByLearnerIdAndStatus(UUID learnerId, ReviewSchedule.Status status);

    boolean existsByLearnerIdAndNodeIdAndStatus(UUID learnerId, UUID nodeId, ReviewSchedule.Status status);

    List<ReviewSchedule> findByStatusOrderByDueAtAsc(ReviewSchedule.Status status, Pageable pageable);

    /** class-analytics batch (teacher class intelligence §2): every review
     * schedule due inside a subject scope in ONE query */
    List<ReviewSchedule> findByStatusAndDueAtLessThanEqualAndNodeIdIn(
            ReviewSchedule.Status status, java.time.Instant dueAt,
            java.util.Collection<UUID> nodeIds);
}
