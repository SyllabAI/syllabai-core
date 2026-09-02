package com.syllabai.infrastructure.storage;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage configuration (prefix {@code syllabai.storage}). Default {@code local}
 * writes under a directory; {@code r2} targets a Cloudflare R2 bucket over its
 * S3-compatible API (ADR: R2 free tier — 10 GB storage, 1M/10M ops, no credit card).
 */
@ConfigurationProperties(prefix = "syllabai.storage")
public record StorageProperties(
        String type,
        Local local,
        R2 r2) {

    public record Local(String baseDir) {
        public Local {
            if (baseDir == null || baseDir.isBlank()) baseDir = "./data/storage";
        }
    }

    public record R2(String accountId, String accessKeyId, String secretAccessKey,
                     String bucket, String region) {
        public R2 {
            if (region == null || region.isBlank()) region = "auto";
        }
    }

    public StorageProperties {
        if (type == null || type.isBlank()) type = "local";
        if (local == null) local = new Local(null);
        if (r2 == null) r2 = new R2(null, null, null, null, null);
    }
}
