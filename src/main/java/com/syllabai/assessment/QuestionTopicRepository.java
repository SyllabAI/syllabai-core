package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionTopicRepository extends JpaRepository<QuestionTopic, UUID> {

    List<QuestionTopic> findByQuestionId(UUID questionId);
}
