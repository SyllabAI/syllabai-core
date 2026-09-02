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
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger requestsToday = new AtomicInteger();
    private final AtomicReference<Instant> lastErrorAt = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>();
    private final java.time.LocalDate day = java.time.LocalDate.now();   // process-lifetime day bucket

    public LlmProviderHealth(boolean configured) {
        this.configured = configured;
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
        if (consecutiveFailures.get() >= 3) {
            cooldownUntil.set(Instant.now().plusSeconds(60));
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
