package com.syllabai.learner;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlashcardRatingRepository extends JpaRepository<FlashcardRating, UUID> {

    /** one learner's rating events, newest first (learner-state view, capped) */
    List<FlashcardRating> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);

    /** latest rating event per card — the card's current rating (dedupe in caller) */
    List<FlashcardRating> findByLearnerIdAndCardIdOrderByOccurredAtDesc(UUID learnerId,
            String cardId, Pageable pageable);

    /**
     * Course-stats aggregate (ADR-029 tranche 4.10): distinct cards this
     * learner has ever rated, any rating — coverage over the append-only
     * trail (re-ratings count once). The learner-state view serves only the
     * latest 50 events, so the full-trail count must be computed here.
     */
    @Query("select count(distinct r.cardId) from FlashcardRating r where r.learnerId = :learnerId")
    long countDistinctCardsByLearnerId(@Param("learnerId") UUID learnerId);
}
