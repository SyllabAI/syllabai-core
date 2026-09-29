package com.syllabai.classroom;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeachingCoverageRepository extends JpaRepository<TeachingCoverage, TeachingCoverage.Pk> {

    /** the class's recorded coverage rows — only what teachers asserted, never fabricated */
    List<TeachingCoverage> findByClassIdOrderBySpecPointNodeIdAsc(UUID classId);

    Optional<TeachingCoverage> findByClassIdAndSpecPointNodeId(UUID classId, UUID specPointNodeId);
}
