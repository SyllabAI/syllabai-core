package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LearnerSelfMarkRepository extends JpaRepository<LearnerSelfMark, UUID> {

    /** all self-marks recorded against one attempt's answers (history view) */
    List<LearnerSelfMark> findByAnswerIdIn(List<UUID> answerIds);
}
