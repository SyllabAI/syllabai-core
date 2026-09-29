package com.syllabai.learner;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MisconceptionStateRepository extends JpaRepository<MisconceptionState, UUID> {

    Optional<MisconceptionState> findByLearnerIdAndMisconceptionNodeId(UUID learnerId, UUID nodeId);

    List<MisconceptionState> findByLearnerIdOrderByProbabilityDesc(UUID learnerId);

    /** class-analytics batch (teacher class intelligence §2): every
     * misconception state attached under a subject scope in ONE query */
    List<MisconceptionState> findByMisconceptionNodeIdIn(Collection<UUID> nodeIds);

    /** class-KG heatmap batch (F-072): every misconception state for a class
     * roster attached under a subject scope in ONE query */
    List<MisconceptionState> findByLearnerIdInAndMisconceptionNodeIdIn(
            Collection<UUID> learnerIds, Collection<UUID> misconceptionNodeIds);
}
