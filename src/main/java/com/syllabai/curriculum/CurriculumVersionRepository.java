package com.syllabai.curriculum;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CurriculumVersionRepository extends JpaRepository<CurriculumVersion, UUID> {

    List<CurriculumVersion> findByStatusOrderByCreatedAtDesc(CurriculumVersion.Status status);

    List<CurriculumVersion> findAllByOrderByCreatedAtDesc();
}
