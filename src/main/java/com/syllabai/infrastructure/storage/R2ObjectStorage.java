package com.syllabai.infrastructure.storage;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Cloudflare R2 {@link ObjectStorage} adapter over the AWS S3 v2 SDK (R2 is
 * S3-API compatible). R2 free tier: 10 GB storage, 1M Class A / 10M Class B ops,
 * free egress — no credit card (ADR-009 / Master Spec §25).
 *
 * <p>The S3 client is built lazily on first use. R2 credentials are optional
 * (render.yaml selects type {@code r2} unconditionally, but only the
 * GLM-OCR/ingestion pipeline touches storage) — a deployment without the
 * {@code SYLLABAI_R2_*} secrets must still boot. Blank credentials therefore
 * never fail startup; the first storage operation fails loudly with the exact
 * missing settings instead. There is deliberately NO silent fallback to local
 * disk — that would write production objects to Render's ephemeral filesystem.</p>
 */
public class R2ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(R2ObjectStorage.class);

    private final StorageProperties.R2 props;
    private final String bucket;
    private volatile S3Client s3;

    public R2ObjectStorage(StorageProperties.R2 props) {
        this.props = props;
        this.bucket = props.bucket();
        if (!isConfigured(props)) {
            log.warn("R2 storage selected but credentials are missing (SYLLABAI_R2_ACCOUNT_ID / "
                    + "SYLLABAI_R2_ACCESS_KEY_ID / SYLLABAI_R2_SECRET_ACCESS_KEY). Booting WITHOUT "
                    + "object storage: storage operations will fail loudly until the secrets are "
                    + "set. Only the GLM-OCR/ingestion pipeline uses storage — the student loop "
                    + "does not need it (render.yaml documents R2 as optional).");
        }
    }

    /** True when the minimum R2 settings are present. */
    static boolean isConfigured(StorageProperties.R2 props) {
        return props.accountId() != null && !props.accountId().isBlank()
                && props.accessKeyId() != null && !props.accessKeyId().isBlank()
                && props.secretAccessKey() != null && !props.secretAccessKey().isBlank()
                && props.bucket() != null && !props.bucket().isBlank();
    }

    private S3Client client() {
        S3Client local = s3;
        if (local == null) {
            synchronized (this) {
                local = s3;
                if (local == null) {
                    if (!isConfigured(props)) {
                        throw new LocalFileObjectStorage.StorageException(
                                "R2 storage is not configured — set SYLLABAI_R2_ACCOUNT_ID, "
                                        + "SYLLABAI_R2_ACCESS_KEY_ID and SYLLABAI_R2_SECRET_ACCESS_KEY "
                                        + "(object storage is optional: only the GLM-OCR/ingestion "
                                        + "pipeline needs it)");
                    }
                    local = S3Client.builder()
                            .endpointOverride(URI.create(
                                    "https://%s.r2.cloudflarestorage.com".formatted(props.accountId())))
                            .region(software.amazon.awssdk.regions.Region.of(props.region()))
                            .credentialsProvider(
                                    software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                                            software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                                    props.accessKeyId(), props.secretAccessKey())))
                            .build();
                    s3 = local;
                }
            }
        }
        return local;
    }

    @Override
    public StoredObject put(String key, String contentType, byte[] bytes) {
        try {
            client().putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(contentType)
                            .contentLength((long) bytes.length)
                            .build(),
                    RequestBody.fromBytes(bytes));
            return new StoredObject(key, bytes.length, null);
        } catch (S3Exception e) {
            throw new LocalFileObjectStorage.StorageException("R2 put failed for " + key, e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        try {
            return Optional.of(client().getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build()).asByteArray());
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new LocalFileObjectStorage.StorageException("R2 get failed for " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            throw new LocalFileObjectStorage.StorageException("R2 delete failed for " + key, e);
        }
    }
}
