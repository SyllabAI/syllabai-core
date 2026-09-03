package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, UUID> {

    List<ExamPaper> findAllByOrderByCreatedAtDesc();

    Optional<ExamPaper> findByPaperCodeAndSessionLabel(String paperCode, String sessionLabel);

    @Query("""
            select p from ExamPaper p
            where p.validationState = com.syllabai.assessment.ExamPaper$ValidationState.SUGGESTED
            order by p.createdAt desc
            """)
    List<ExamPaper> findSuggested();

    @Query("""
            select p from ExamPaper p
            where p.validationState = com.syllabai.assessment.ExamPaper$ValidationState.VALIDATED
            order by p.createdAt desc
            """)
    List<ExamPaper> findValidated();
}
