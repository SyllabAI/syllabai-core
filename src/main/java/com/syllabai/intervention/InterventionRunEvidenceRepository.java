package com.syllabai.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface InterventionRunEvidenceRepository extends JpaRepository<InterventionRunEvidence, UUID> {
    List<InterventionRunEvidence> findByRunIdOrderByCapturedAtAsc(UUID runId);
}
