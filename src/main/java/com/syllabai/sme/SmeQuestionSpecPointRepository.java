package com.syllabai.sme;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SmeQuestionSpecPointRepository
        extends JpaRepository<QuestionSpecPoint, UUID> {

    List<QuestionSpecPoint> findByQuestionId(UUID questionId);
}
