package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionPartRepository extends JpaRepository<QuestionPart, UUID> {

    List<QuestionPart> findByQuestionVersionIdOrderByOrdering(UUID questionVersionId);

    Optional<QuestionPart> findByQuestionVersionIdAndLabel(UUID questionVersionId, String label);
}
