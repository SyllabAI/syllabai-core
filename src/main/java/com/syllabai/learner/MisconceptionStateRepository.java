package com.syllabai.learner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MisconceptionStateRepository extends JpaRepository<MisconceptionState, UUID> {

    Optional<MisconceptionState> findByLearnerIdAndMisconceptionNodeId(UUID learnerId, UUID nodeId);

    List<MisconceptionState> findByLearnerIdOrderByProbabilityDesc(UUID learnerId);
}
