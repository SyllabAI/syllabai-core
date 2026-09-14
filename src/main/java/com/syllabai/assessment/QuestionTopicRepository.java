package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface QuestionTopicRepository extends JpaRepository<QuestionTopic, UUID> {

    List<QuestionTopic> findByQuestionId(UUID questionId);

    /** §10 re-mapping: drop the question's rows before the replacement set is written */
    @Modifying
    void deleteByQuestionId(UUID questionId);
}
