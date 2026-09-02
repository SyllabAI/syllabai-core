package com.syllabai.infrastructure.storage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the {@link ObjectStorage} implementation from {@code syllabai.storage.type}.
 */
@Configuration
public class ObjectStorageConfig {

    @Bean
    public ObjectStorage objectStorage(StorageProperties properties) {
        return switch (properties.type()) {
            case "local" -> new LocalFileObjectStorage(java.nio.file.Path.of(
                    properties.local().baseDir()));
            case "r2" -> new R2ObjectStorage(properties.r2());
            default -> throw new IllegalStateException(
                    "unknown syllabai.storage.type: " + properties.type()
                    + " (expected 'local' or 'r2')");
        };
    }
}
