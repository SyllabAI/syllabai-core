package com.syllabai.assessment;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarkPointRepository extends JpaRepository<MarkPoint, UUID> {

    /** eager-fetches the target part so detached views (bridge reporting, review
     *  surfaces) can read questionPartId without a session */
    @EntityGraph(attributePaths = "questionPart")
    List<MarkPoint> findByMarkSchemeIdOrderByOrdering(UUID markSchemeId);

    List<MarkPoint> findByQuestionPartIdOrderByOrdering(UUID questionPartId);
}
