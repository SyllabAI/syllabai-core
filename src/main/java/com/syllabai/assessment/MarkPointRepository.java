package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarkPointRepository extends JpaRepository<MarkPoint, UUID> {

    List<MarkPoint> findByMarkSchemeIdOrderByOrdering(UUID markSchemeId);

    List<MarkPoint> findByQuestionPartIdOrderByOrdering(UUID questionPartId);
}
