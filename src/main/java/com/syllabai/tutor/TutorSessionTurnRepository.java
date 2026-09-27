package com.syllabai.tutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Transcript of one session, seq-ordered (append-only rows). */
public interface TutorSessionTurnRepository extends JpaRepository<TutorSessionTurn, UUID> {

    List<TutorSessionTurn> findBySessionIdOrderBySeq(UUID sessionId);

    Optional<TutorSessionTurn> findTopBySessionIdOrderBySeqDesc(UUID sessionId);
}
