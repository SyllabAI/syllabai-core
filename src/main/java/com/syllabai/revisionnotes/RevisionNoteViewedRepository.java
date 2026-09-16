package com.syllabai.revisionnotes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RevisionNoteViewedRepository extends JpaRepository<RevisionNoteViewed, UUID> {

    Optional<RevisionNoteViewed> findByUserIdAndNoteId(UUID userId, String noteId);

    List<RevisionNoteViewed> findByUserIdOrderByViewedAtDesc(UUID userId);

    /**
     * Sweeps progress rows whose note no longer exists after a replace-all
     * corpus re-ingestion (note_id is a loose reference by design — no FK).
     */
    @Modifying
    @Query("delete from RevisionNoteViewed v where not exists "
            + "(select 1 from RevisionNote n where n.noteId = v.noteId)")
    int deleteOrphans();
}
