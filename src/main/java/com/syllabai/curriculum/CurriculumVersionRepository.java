package com.syllabai.curriculum;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CurriculumVersionRepository extends JpaRepository<CurriculumVersion, UUID> {

    List<CurriculumVersion> findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status status);

    List<CurriculumVersion> findAllByOrderByCreatedAtDesc();

    /** T-010 curriculum ingestion resolves an existing version by identity. */
    Optional<CurriculumVersion> findByBoardAndQualificationAndCode(
            String board, String qualification, String code);

    /**
     * V53 per-course tutor scoping (ADR-030): the exact-match candidates for
     * {@code CurriculumScopeResolver.resolveForCourse} — a hub-supplied
     * course ref resolves against core's OWN registry by EXACT code, ACTIVE
     * only. Deliberately no fuzzy variants: a ref that does not exactly name
     * a core curriculum is unresolved, and unresolved refuses.
     */
    List<CurriculumVersion> findByCodeAndStatus(String code, CurriculumVersion.Status status);
}
