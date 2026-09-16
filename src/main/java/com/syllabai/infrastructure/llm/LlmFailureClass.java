package com.syllabai.infrastructure.llm;

/**
 * Provider-neutral, structured classification of an LLM provider failure (ADR-023).
 * Classification happens at the provider/adapter boundary — the adapter maps SDK /
 * HTTP exceptions onto this enum once, and everything downstream (failover policy,
 * health snapshots, admin observability, log analysis) consumes the structured class
 * instead of parsing exception strings.
 *
 * <p>Failover semantics (ADR-023; existing §26.1 behaviour preserved): the chain
 * fails over on any member failure — one bounded attempt per provider per request.
 * Configuration failures additionally suppress <em>future</em> attempts for the rest
 * of the UTC day, because retrying a dead key or a retired model within the same
 * deployment cannot succeed:</p>
 *
 * <ul>
 *   <li>{@link #AUTHENTICATION_FAILURE} — dead/revoked key; skip future attempts
 *       (until end of UTC day) rather than burning latency on every request.</li>
 *   <li>{@link #MODEL_NOT_FOUND} — retired/unknown model id; a configuration
 *       defect, not a transient outage — skip future attempts (until end of UTC day),
 *       never "retry harder".</li>
 * </ul>
 */
public enum LlmFailureClass {

    /** 429 / quota exhaustion at the provider. Transient: fail over. */
    RATE_LIMITED,

    /** 401/403 — credential rejected. Configuration failure: skip future attempts. */
    AUTHENTICATION_FAILURE,

    /** Provider unreachable / 5xx. Transient: fail over. */
    PROVIDER_UNAVAILABLE,

    /** Call exceeded the configured per-call timeout. Transient: fail over. */
    TIMEOUT,

    /** 400/422 — request rejected. Never blindly retried against the same provider. */
    BAD_REQUEST,

    /** 404 / unknown-model. Configuration failure: skip future attempts. */
    MODEL_NOT_FOUND,

    /** Response arrived but could not be parsed/validated. Bounded failover. */
    INVALID_RESPONSE,

    /** Unclassified failure — follow the existing conservative behaviour. */
    UNKNOWN;

    /**
     * True when the failure means the provider's CONFIGURATION is broken (dead key,
     * retired model): retrying within this deployment cannot succeed, so the provider
     * skips future attempts until the UTC day rolls over (or a redeploy changes the
     * configuration). Transient outages (429, 5xx, timeouts) are NOT configuration
     * failures — the existing failure-threshold/cooldown semantics handle them.
     */
    public boolean isConfigurationFailure() {
        return this == AUTHENTICATION_FAILURE || this == MODEL_NOT_FOUND;
    }
}
