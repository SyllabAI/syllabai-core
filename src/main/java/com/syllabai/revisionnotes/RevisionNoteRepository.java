package com.syllabai.revisionnotes;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionNoteRepository extends JpaRepository<RevisionNote, String> {

    /**
     * Canonical corpus ordering: topic → subtopic → note. Index building and
     * prev/next computation both depend on this exact ordering.
     */
    List<RevisionNote> findAllByOrderByTopicOrderAscSubtopicOrderAscNoteOrderAsc();

    long countByCorpusVersion(String corpusVersion);
}
