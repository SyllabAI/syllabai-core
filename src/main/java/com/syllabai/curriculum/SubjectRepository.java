package com.syllabai.curriculum;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubjectRepository extends JpaRepository<Subject, UUID> {

    @EntityGraph(attributePaths = "curriculumVersion")
    List<Subject> findByCurriculumVersionIdOrderByCode(UUID curriculumVersionId);

    @EntityGraph(attributePaths = "curriculumVersion")
    Optional<Subject> findByCode(String code);

    /** T-010 curriculum ingestion resolves a subject inside one curriculum version. */
    Optional<Subject> findByCurriculumVersionIdAndCode(UUID curriculumVersionId, String code);

    @EntityGraph(attributePaths = "curriculumVersion")
    List<Subject> findAllByOrderByCode();
}
