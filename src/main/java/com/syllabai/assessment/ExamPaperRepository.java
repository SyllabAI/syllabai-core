package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, UUID> {

    List<ExamPaper> findAllByOrderByCreatedAtDesc();

    List<ExamPaper> findAllBySubjectIdOrderByCreatedAtDesc(UUID subjectId);

    /** T-C07 scope ownership: does this subject carry any exam-paper surface? */
    boolean existsBySubjectId(UUID subjectId);

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

    /**
     * V20 paper-level serving gate: ids of papers whose state must block serving
     * of EVERYTHING under them (a rejected or flagged paper signals a systematic
     * defect — wrong source, mis-placement, mass extraction failure). Small
     * result by construction (content-review states, not learner data).
     */
    @Query("""
            select p.id from ExamPaper p
            where p.validationState in (com.syllabai.assessment.ExamPaper$ValidationState.REJECTED,
                                        com.syllabai.assessment.ExamPaper$ValidationState.FLAGGED)
            """)
    List<UUID> findIdsBlockingServing();
}
