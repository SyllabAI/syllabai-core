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

    /**
     * FULL trail, card-grouped and chronological within the card — the
     * review-schedule feed's read (T-C53): the schedule is derived from this
     * at READ time and persisted nowhere (ADR-031 doctrine). The id is the
     * final tiebreaker so the read is deterministic even on an occurred_at
     * tie (a production non-event — the rating flow IT paces writes like a
     * human deck flip — but the derived schedule must not depend on row
     * fetch order regardless).
     */
    List<FlashcardRating> findByLearnerIdOrderByCardIdAscOccurredAtAscIdAsc(UUID learnerId);

    /**
     * KEYSET page 1 of the raw trail, newest first (T-C61 — the bounded
     * raw-trail read, GET /api/v1/learners/me/flashcard-rating-trail): the
     * order is the total order {@code (occurred_at DESC, id DESC)} — the id
     * is the deterministic tiebreaker because V47's UUID id carries no time
     * information. Page 2+ uses {@link #pageByLearnerNewestFirstAfter} with
     * the TrailCursor position of this page's last row; the two methods MUST
     * keep the same order clause or the walk loses its keyset guarantees.
     */
    List<FlashcardRating> findByLearnerIdOrderByOccurredAtDescIdDesc(UUID learnerId,
            Pageable pageable);

    /**
     * KEYSET pages 2+ of the raw trail (T-C61): strictly AFTER the cursor
     * position — the exclusive-tuple predicate {@code (occurred_at, id) <
     * (beforeTs, beforeId)} expressed as its disjunctive normal form. The
     * cursor pair comes from {@link TrailCursor} (server-issued, opaque);
     * exclusive comparison means a row added with EXACTLY the cursor's
     * position and a smaller id could still precede it — impossible for
     * pages already served (append-only trail, ids never reused), which is
     * what makes the walk gap-free and duplicate-free.
     */
    @Query("""
            select r from FlashcardRating r
            where r.learnerId = :learnerId
              and (r.occurredAt < :beforeTs
                   or (r.occurredAt = :beforeTs and r.id < :beforeId))
            order by r.occurredAt desc, r.id desc
            """)
    List<FlashcardRating> pageByLearnerNewestFirstAfter(
            @Param("learnerId") UUID learnerId,
            @Param("beforeTs") java.time.Instant beforeTs,
            @Param("beforeId") UUID beforeId,
            Pageable pageable);
}
