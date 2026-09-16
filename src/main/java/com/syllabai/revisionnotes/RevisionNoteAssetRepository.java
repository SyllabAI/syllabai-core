package com.syllabai.revisionnotes;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionNoteAssetRepository extends JpaRepository<RevisionNoteAsset, String> {

    Optional<RevisionNoteAsset> findByFilename(String filename);
}
