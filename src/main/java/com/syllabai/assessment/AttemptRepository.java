package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @EntityGraph(attributePaths = "question")
    List<Attempt> findByLearnerIdOrderByCreatedAtDesc(UUID learnerId, Pageable pageable);

    long countByLearnerId(UUID learnerId);
}
