package com.syllabai.tutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Transcript of one session, seq-ordered (append-only rows). */
public interface TutorSessionTurnRepository extends JpaRepository<TutorSessionTurn, UUID> {

    List<TutorSessionTurn> findBySessionIdOrderBySeq(UUID sessionId);

    Optional<TutorSessionTurn> findTopBySessionIdOrderBySeqDesc(UUID sessionId);

    /**
     * The seq=1 turns of the given sessions (s143 conversation list): the
     * append contract writes the user question at seq 1, so these are the
     * opening questions the list titles are derived from. One query for the
     * whole page of sessions — no per-session round trips.
     */
    List<TutorSessionTurn> findBySessionIdInAndSeq(Collection<UUID> sessionIds, int seq);

    /**
     * Turn counts for the given sessions in one grouped query (s143): feeds
     * the conversation list's "n turns" line without loading any transcript
     * rows.
     */
    @Query("""
            select t.sessionId as sessionId, count(t) as turnCount
            from TutorSessionTurn t
            where t.sessionId in :sessionIds
            group by t.sessionId
            """)
    List<SessionTurnCount> countBySessionIdIn(@Param("sessionIds") Collection<UUID> sessionIds);

    /** Owned-path delete support (s143): a session's turns go before the row.
     *  One statement — a derived {@code deleteBy} would load and delete each
     *  turn row individually; transcripts are small but the delete is not the
     *  place to spend the round trips. */
    @Modifying
    @Query("delete from TutorSessionTurn t where t.sessionId = :sessionId")
    void deleteBySessionId(@Param("sessionId") UUID sessionId);

    /** Grouped count projection for {@link #countBySessionIdIn}. */
    interface SessionTurnCount {
        UUID getSessionId();

        long getTurnCount();
    }
}
