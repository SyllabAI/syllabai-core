package com.syllabai.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StruggleInferenceRepository extends JpaRepository<StruggleInference, UUID> {
    List<StruggleInference> findByLearnerIdAndTopicNodeIdAndExpiresAtAfterOrderByProbabilityDesc(
            UUID learnerId, UUID topicNodeId, Instant now);

    List<StruggleInference> findByLearnerIdAndExpiresAtAfterOrderByProbabilityDesc(
            UUID learnerId, Instant now);
}
