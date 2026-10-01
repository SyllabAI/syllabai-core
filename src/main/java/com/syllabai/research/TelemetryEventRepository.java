package com.syllabai.research;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TelemetryEventRepository extends JpaRepository<TelemetryEvent, UUID> {

    List<TelemetryEvent> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);

    /**
     * Full-stream scan by event type (S2/ADR-033 calibration instrument reads
     * BKT_UPDATED). Fine at Cycle-1 volume (hundreds of rows); paged variants
     * are the follow-up when the stream outgrows memory.
     */
    List<TelemetryEvent> findByTypeOrderByOccurredAtAsc(TelemetryEvent.Type type);
}
