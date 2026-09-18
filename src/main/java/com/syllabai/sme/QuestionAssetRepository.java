package com.syllabai.sme;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionAssetRepository extends JpaRepository<QuestionAsset, String> {

    Optional<QuestionAsset> findByFilename(String filename);
}
