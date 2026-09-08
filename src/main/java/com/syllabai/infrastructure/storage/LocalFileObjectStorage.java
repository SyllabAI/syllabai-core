package com.syllabai.infrastructure.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * Development/filesystem {@link ObjectStorage} adapter (Master Spec §25 lists
 * "local development filesystem" as a valid implementation).
 */
public class LocalFileObjectStorage implements ObjectStorage {

    private final Path baseDir;

    public LocalFileObjectStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public StoredObject put(String key, String contentType, byte[] bytes) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new StorageException("failed to write object " + key, e);
        }
        return new StoredObject(key, bytes.length, null);
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            throw new StorageException("failed to read object " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("failed to delete object " + key, e);
        }
    }

    /** Guards against path traversal outside the base directory. */
    private Path resolve(String key) {
        if (!StringUtils.hasText(key)) {
            throw new StorageException("storage key must not be blank", null);
        }
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new StorageException("storage key escapes base directory: " + key, null);
        }
        return resolved;
    }

    public static class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
