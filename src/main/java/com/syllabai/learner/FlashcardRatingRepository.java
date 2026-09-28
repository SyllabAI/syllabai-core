package com.syllabai.learner;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlashcardRatingRepository extends JpaRepository<FlashcardRating, UUID> {

    /** one learner's rating events, newest first (learner-state view, capped) */
    List<FlashcardRating> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);

    /** latest rating event per card — the card's current rating (dedupe in caller) */
    List<FlashcardRating> findByLearnerIdAndCardIdOrderByOccurredAtDesc(UUID learnerId,
            String cardId, Pageable pageable);
}
