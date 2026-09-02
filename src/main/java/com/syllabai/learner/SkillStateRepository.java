package com.syllabai.learner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillStateRepository extends JpaRepository<SkillState, UUID> {

    Optional<SkillState> findByLearnerIdAndNodeId(UUID learnerId, UUID nodeId);

    List<SkillState> findByLearnerIdOrderByLastPracticedAtDesc(UUID learnerId);

    List<SkillState> findByLastPracticedAtBefore(java.time.Instant cutoff, Pageable pageable);

    List<SkillState> findByLearnerIdAndNodeIdIn(UUID learnerId, List<UUID> nodeIds);

    long countByLearnerId(UUID learnerId);
}
