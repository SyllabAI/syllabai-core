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

    /**
     * F-022 tranche 2 (the paper-PDF join): the exam paper a QP/MS citation
     * document belongs to, by the paper's BUSINESS document id (what
     * {@code exam_papers.question_paper_document_id / mark_scheme_document_id}
     * store — not the per-version row id). Newest row wins in the (theoretically
     * impossible, defensively bounded) case of several papers linking one id.
     */
    @Query("""
            select p from ExamPaper p
            where p.questionPaperDocumentId = :documentId
               or p.markSchemeDocumentId = :documentId
            order by p.createdAt desc
            """)
    List<ExamPaper> findAllByLinkedDocumentId(@Param("documentId") String documentId);

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
