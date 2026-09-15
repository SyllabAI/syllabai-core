package com.syllabai.learner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorTopicEngagementRepository extends JpaRepository<TutorTopicEngagement, UUID> {

    /** recent engagements of one learner, newest first (learner-state view) */
    List<TutorTopicEngagement> findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
            UUID learnerId, Instant since);

    /** class-analytics batch (teacher class intelligence §2): every engagement
     * on topics inside a subject scope in ONE query — evidence counts only,
     * never mastery */
    List<TutorTopicEngagement> findByNodeIdIn(java.util.Collection<UUID> nodeIds);

    /**
     * Per-topic engagement counts inside the window (NBA T7a): the learner's
     * own interest signal — which matched topics they have been asking about.
     */
    @Query("""
            select e.nodeId, count(e)
            from TutorTopicEngagement e
            where e.learnerId = :learnerId and e.occurredAt >= :since
            group by e.nodeId
            """)
    List<Object[]> countByLearnerSinceGroupedByNode(@Param("learnerId") UUID learnerId,
                                                    @Param("since") Instant since);
}
