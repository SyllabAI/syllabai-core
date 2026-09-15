package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionTopicRepository extends JpaRepository<QuestionTopic, UUID> {

    List<QuestionTopic> findByQuestionId(UUID questionId);

    /**
     * Sprint-2 §10 (weakness targeting): every topic mapping of the given
     * questions in ONE query — lets the Test Builder count, per topic, the
     * servable questions reachable through primary OR secondary mappings with
     * the same rule /preview assembles by. Read-only, batched by construction.
     */
    List<QuestionTopic> findByQuestionIdIn(java.util.Collection<UUID> questionIds);

    /** §10 re-mapping: drop the question's rows before the replacement set is written */
    @Modifying
    void deleteByQuestionId(UUID questionId);

    /**
     * Review-queue v3 (sprint 2 §7): every topic mapping of every paper's
     * questions in ONE query — rows [paperId(UUID), nodeId(UUID)]. Feeds the
     * per-paper mapping coverage and the novel-coverage signal (mapped topics
     * outside the set the pilot can already practice). Read-only.
     */
    @Query("""
            select qt.question.examPaperId, qt.nodeId
            from QuestionTopic qt
            where qt.question.examPaperId is not null
            """)
    List<Object[]> findMappingsByPaper();
} 
