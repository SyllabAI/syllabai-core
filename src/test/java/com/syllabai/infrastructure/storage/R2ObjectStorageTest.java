package com.syllabai.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * T-036 regression: render.yaml sets {@code SYLLABAI_STORAGE_TYPE=r2}
 * unconditionally while the {@code SYLLABAI_R2_*} secrets are optional
 * (sync:false, only the GLM-OCR/ingestion pipeline uses storage). A deployment
 * without R2 credentials must still boot; storage operations must then fail
 * loudly with the exact missing settings — never silently fall back to local
 * (ephemeral) disk.
 */
class R2ObjectStorageTest {

    private static final StorageProperties.R2 UNCONFIGURED =
            new StorageProperties.R2(null, null, null, null, null);

    @Test
    void blankCredentialsDoNotFailConstruction() {
        // render.yaml's exact shape: type=r2, no SYLLABAI_R2_* set.
        // AwsBasicCredentials rejects blank keys at S3Client build time —
        // construction must defer that to first use.
        assertThatCode(() -> new R2ObjectStorage(UNCONFIGURED))
                .doesNotThrowAnyException();
        assertThat(R2ObjectStorage.isConfigured(UNCONFIGURED)).isFalse();
    }

    @Test
    void storageOperationFailsLoudlyWithClearMessageWhenUnconfigured() {
        R2ObjectStorage storage = new R2ObjectStorage(UNCONFIGURED);
        assertThatThrownBy(() -> storage.put("some/key", "application/pdf", new byte[] {1}))
                .isInstanceOf(LocalFileObjectStorage.StorageException.class)
                .hasMessageContaining("R2 storage is not configured")
                .hasMessageContaining("SYLLABAI_R2_ACCESS_KEY_ID");
        assertThatThrownBy(() -> storage.get("some/key"))
                .isInstanceOf(LocalFileObjectStorage.StorageException.class)
                .hasMessageContaining("R2 storage is not configured");
    }

    @Test
    void isConfiguredTrueWhenAllSecretsPresent() {
        StorageProperties.R2 configured =
                new StorageProperties.R2("account", "access-key", "secret", "bucket", null);
        assertThat(R2ObjectStorage.isConfigured(configured)).isTrue();
        // region defaults to "auto" via the properties record
        assertThat(configured.region()).isEqualTo("auto");
    }
}
