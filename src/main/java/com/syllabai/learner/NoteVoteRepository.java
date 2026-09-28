package com.syllabai.learner;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteVoteRepository extends JpaRepository<NoteVote, UUID> {

    /** one learner's vote events, newest first (learner-state view, capped) */
    List<NoteVote> findByLearnerIdOrderByOccurredAtDesc(UUID learnerId, Pageable pageable);

    /** latest vote event per note — the note's current vote (dedupe in caller) */
    List<NoteVote> findByLearnerIdAndNoteIdOrderByOccurredAtDesc(UUID learnerId,
            String noteId, Pageable pageable);
}
