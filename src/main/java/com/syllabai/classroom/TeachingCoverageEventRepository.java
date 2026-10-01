package com.syllabai.classroom;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeachingCoverageEventRepository
        extends JpaRepository<TeachingCoverageEvent, UUID> {

    /** the per-point audit trail, newest first — served verbatim, never summarized */
    List<TeachingCoverageEvent> findByClassIdAndSpecPointNodeIdOrderByCreatedAtDesc(
            UUID classId, UUID specPointNodeId);
}
