package com.syllabai.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JSON request-body budgets (deep-audit 09-28 M4). Spring's multipart limits
 * cover {@code multipart/form-data} uploads only — plain JSON bodies (teacher
 * curriculum drafts, past-paper drafts, GLM OCR canonical pairs, marking
 * batches) had NO cap: a single oversized POST could make Jackson allocate the
 * full document and OOM the single-instance pod. The filter rejects bodies
 * over the budget with 413 before any controller reads them.
 *
 * <p>The default (2 MiB) sits far above every legitimate body observed in the
 * repo and its fixtures (the largest is the GLM OCR canonical pair at a few
 * hundred KB) and far below anything that threatens the heap.</p>
 *
 * @param enabled          master switch
 * @param maxJsonBodyBytes maximum accepted JSON body size in bytes
 */
@ConfigurationProperties(prefix = "syllabai.http")
public record JsonBodyLimitProperties(Boolean enabled, Long maxJsonBodyBytes) {

    public JsonBodyLimitProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxJsonBodyBytes = maxJsonBodyBytes == null ? 2L * 1024 * 1024 : maxJsonBodyBytes;
    }

    /** compact test fixture with explicit values */
    public static JsonBodyLimitProperties of(boolean enabled, long maxJsonBodyBytes) {
        return new JsonBodyLimitProperties(enabled, maxJsonBodyBytes);
    }
}
