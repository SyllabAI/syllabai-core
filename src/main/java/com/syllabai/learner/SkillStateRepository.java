package com.syllabai.learner;

import java.util.Collection;
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

    /** class-analytics batch (teacher class intelligence §2): every skill
     * state inside a subject scope in ONE query — never per-learner loops */
    List<SkillState> findByNodeIdIn(Collection<UUID> nodeIds);

    /** class-KG heatmap batch (F-072): every skill state for a class roster
     * (the enabled ClassMember rows) inside a subject scope in ONE query —
     * the per-learner loop stays banned */
    List<SkillState> findByLearnerIdInAndNodeIdIn(Collection<UUID> learnerIds,
                                                  Collection<UUID> nodeIds);
}
