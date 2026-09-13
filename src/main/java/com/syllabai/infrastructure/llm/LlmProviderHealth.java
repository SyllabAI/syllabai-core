package com.syllabai.infrastructure.llm;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider health + rate-budget tracking (§26.1: failover with health and rate-budget
 * tracking per provider; §32 observability).
 */
public final class LlmProviderHealth {

    private final boolean configured;
    private final int failureThreshold;
    private final int cooldownSeconds;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger requestsToday = new AtomicInteger();
    private final AtomicReference<Instant> lastErrorAt = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>();
    private final java.time.LocalDate day = java.time.LocalDate.now();   // process-lifetime day bucket

    /** Defaults kept for existing callers; equal to the §26.1 documented baseline. */
    public LlmProviderHealth(boolean configured) {
        this(configured, 3, 60);
    }

    /** Threshold/cooldown wired from {@code syllabai.llm.chain.*} (they were
     * documented config; hard-coding them here made the properties dead). */
    public LlmProviderHealth(boolean configured, int failureThreshold, int cooldownSeconds) {
        this.configured = configured;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownSeconds = Math.max(1, cooldownSeconds);
    }

    public boolean configured() {
        return configured;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        requestsToday.incrementAndGet();
    }

    public void recordFailure(String message) {
        consecutiveFailures.incrementAndGet();
        requestsToday.incrementAndGet();
        lastErrorAt.set(Instant.now());
        lastErrorMessage.set(message);
        if (consecutiveFailures.get() >= failureThreshold) {
            cooldownUntil.set(Instant.now().plusSeconds(cooldownSeconds));
        }
    }

    public boolean inCooldown() {
        Instant until = cooldownUntil.get();
        return until != null && Instant.now().isBefore(until);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                configured,
                consecutiveFailures.get(),
                requestsToday.get(),
                lastErrorAt.get(),
                lastErrorMessage.get(),
                cooldownUntil.get());
    }

    /**
     * Immutable observability view.
     */
    public record Snapshot(
            boolean configured,
            int consecutiveFailures,
            int requestsToday,
            Instant lastErrorAt,
            String lastErrorMessage,
            Instant cooldownUntil) {
    }
}
