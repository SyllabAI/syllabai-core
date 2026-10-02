package com.syllabai.research;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, UUID> {

    List<TelemetryEvent> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);

    /** full stream of one event type in emission order (calibration instrument, S2/ADR-033) */
    List<TelemetryEvent> findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type type);
}
