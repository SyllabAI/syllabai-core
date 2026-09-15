package com.syllabai.intervention;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface InterventionRunRepository extends JpaRepository<InterventionRun, UUID> {
}
