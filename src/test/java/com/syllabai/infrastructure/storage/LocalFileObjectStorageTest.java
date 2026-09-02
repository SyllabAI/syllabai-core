package com.syllabai.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileObjectStorageTest {

    @TempDir
    java.nio.file.Path tempDir;

    private ObjectStorage storage;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        storage = new LocalFileObjectStorage(tempDir);
    }

    @Test
    @DisplayName("put + get round-trips bytes")
    void putGetRoundTrip() {
        byte[] bytes = "past-paper-bytes".getBytes();
        storage.put("papers/wch11-2022.pdf", "application/pdf", bytes);
        assertThat(storage.get("papers/wch11-2022.pdf")).contains(bytes);
        assertThat(storage.exists("papers/wch11-2022.pdf")).isTrue();
    }

    @Test
    @DisplayName("get on missing key returns empty")
    void missingKeyIsEmpty() {
        assertThat(storage.get("no/such/key.bin")).isEmpty();
        assertThat(storage.exists("no/such/key.bin")).isFalse();
    }

    @Test
    @DisplayName("delete removes the object")
    void deleteRemoves() {
        storage.put("tmp/x.txt", "text/plain", "x".getBytes());
        storage.delete("tmp/x.txt");
        assertThat(storage.exists("tmp/x.txt")).isFalse();
    }

    @Test
    @DisplayName("path traversal outside the base directory is blocked")
    void blocksPathTraversal() {
        assertThatThrownBy(() -> storage.put("../escape.txt", "text/plain", "x".getBytes()))
                .isInstanceOf(LocalFileObjectStorage.StorageException.class);
        assertThat(Files.exists(tempDir.resolve("../escape.txt").normalize())).isFalse();
    }

    @Test
    @DisplayName("blank keys are rejected")
    void rejectsBlankKey() {
        assertThatThrownBy(() -> storage.put(" ", "text/plain", "x".getBytes()))
                .isInstanceOf(LocalFileObjectStorage.StorageException.class);
    }
}
