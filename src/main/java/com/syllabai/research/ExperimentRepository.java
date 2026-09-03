package com.syllabai.research;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    Optional<Experiment> findByExperimentKey(String experimentKey);
}
