package com.syllabai.research;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, UUID> {

    Optional<ModelVersion> findFirstByRegistryKeyOrderByCreatedAtDesc(String registryKey);
}
