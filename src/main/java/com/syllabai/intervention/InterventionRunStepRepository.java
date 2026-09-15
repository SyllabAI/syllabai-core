package com.syllabai.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface InterventionRunStepRepository extends JpaRepository<InterventionRunStep, UUID> {
    List<InterventionRunStep> findByRunIdOrderBySequenceNoAsc(UUID runId);
}
