package com.syllabai.research;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, UUID> {

    List<TelemetryEvent> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);
}
