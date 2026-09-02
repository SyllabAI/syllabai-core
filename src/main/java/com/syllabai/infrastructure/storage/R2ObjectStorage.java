package com.syllabai.infrastructure.storage;

import java.net.URI;
import java.util.Optional;
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
 */
public class R2ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;

    public R2ObjectStorage(StorageProperties.R2 props) {
        this.bucket = props.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(
                        "https://%s.r2.cloudflarestorage.com".formatted(props.accountId())))
                .region(software.amazon.awssdk.regions.Region.of(props.region()))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                props.accessKeyId(), props.secretAccessKey())))
                .build();
    }

    @Override
    public StoredObject put(String key, String contentType, byte[] bytes) {
        try {
            s3.putObject(PutObjectRequest.builder()
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
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder()
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
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            throw new LocalFileObjectStorage.StorageException("R2 delete failed for " + key, e);
        }
    }
}
