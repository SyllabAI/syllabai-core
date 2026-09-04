package com.syllabai.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StruggleInferenceRepository extends JpaRepository<StruggleInference, UUID> {

    /**
     * Active (un-expired, un-superseded) inferences for one (learner, topic, type) —
     * the candidates superseded when fresh evidence produces a replacement inference.
     */
    List<StruggleInference> findByLearnerIdAndTopicNodeIdAndTypeAndExpiresAtAfterAndSupersededAtIsNull(
            UUID learnerId, UUID topicNodeId, StruggleType type, Instant now);

    /**
     * All active inferences for a learner, deterministic order:
     * probability DESC then generatedAt DESC (fully ordered — no ties left to the database).
     */
    List<StruggleInference> findByLearnerIdAndExpiresAtAfterAndSupersededAtIsNullOrderByProbabilityDescGeneratedAtDesc(
            UUID learnerId, Instant now);
}
