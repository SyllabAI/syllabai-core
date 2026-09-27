package com.syllabai.tutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Sessions by recency for one learner (hydration picks the newest). */
public interface TutorSessionRepository extends JpaRepository<TutorSession, UUID> {

    Optional<TutorSession> findFirstByLearnerIdOrderByLastActiveAtDesc(UUID learnerId);

    /** ownership-resolved fetch: null for a session another learner owns */
    Optional<TutorSession> findByIdAndLearnerId(UUID id, UUID learnerId);

    List<TutorSession> findByLearnerIdOrderByLastActiveAtDesc(UUID learnerId);
}
