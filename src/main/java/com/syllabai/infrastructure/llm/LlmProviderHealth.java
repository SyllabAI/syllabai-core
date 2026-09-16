package com.syllabai.infrastructure.llm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider health + rate-budget tracking (§26.1: failover with health and rate-budget
 * tracking per provider; §32 observability; ADR-023 structured failures + budget).
 *
 * <p>Daily budget semantics (ADR-023): {@code dailyBudgetPerProvider} is a
 * <strong>configured local routing guard</strong> — a ceiling on the requests THIS
 * application routes at the provider per UTC day. It is NOT a claim about the
 * provider's official quota and must never be documented as one. Both successful and
 * failed attempts count (the guard bounds runaway routing, not billing). A provider
 * at {@code requestsToday >= dailyBudget} is ineligible ({@link #budgetExhausted()})
 * until the UTC day rolls over; the chain then fails over to the next provider.</p>
 *
 * <p>Configuration failures ({@link LlmFailureClass#AUTHENTICATION_FAILURE},
 * {@link LlmFailureClass#MODEL_NOT_FOUND}) suppress future attempts until the end of
 * the UTC day: a dead key or a retired model cannot heal mid-deployment, so retrying
 * them on every request only burns latency (ADR-023). Transient failures keep the
 * existing consecutive-failure threshold / cooldown semantics.</p>
 */
public final class LlmProviderHealth {

    private final boolean enabled;
    private final boolean configured;
    private final int failureThreshold;
    private final int cooldownSeconds;
    /** <= 0 = unlimited (no local budget guard). */
    private final int dailyBudget;
    private final String effectiveModel;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger requestsToday = new AtomicInteger();
    private final AtomicReference<Instant> lastErrorAt = new AtomicReference<>();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
    private final AtomicReference<LlmFailureClass> lastFailureClass = new AtomicReference<>();
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>();
    private final AtomicReference<LocalDate> day = new AtomicReference<>();
    private final java.util.function.Supplier<LocalDate> clock;

    /** Defaults kept for existing callers; equal to the §26.1 documented baseline. */
    public LlmProviderHealth(boolean configured) {
        this(configured, configured, 3, 60, 0, null);
    }

    /** Threshold/cooldown wired from {@code syllabai.llm.chain.*} (they were
     * documented config; hard-coding them here made the properties dead). */
    public LlmProviderHealth(boolean configured, int failureThreshold, int cooldownSeconds) {
        this(configured, configured, failureThreshold, cooldownSeconds, 0, null);
    }

    /** Full wiring (ADR-023): enabled/configured distinction, local daily budget,
     * provider default model for observability. */
    public LlmProviderHealth(boolean enabled, boolean configured, int failureThreshold,
                             int cooldownSeconds, int dailyBudget, String effectiveModel) {
        this(enabled, configured, failureThreshold, cooldownSeconds, dailyBudget,
                effectiveModel, () -> LocalDate.now(ZoneOffset.UTC));
    }

    /** Package-private test wiring: injectable UTC-day clock for rollover tests. */
    LlmProviderHealth(boolean enabled, boolean configured, int failureThreshold,
                      int cooldownSeconds, int dailyBudget, String effectiveModel,
                      java.util.function.Supplier<LocalDate> clock) {
        this.enabled = enabled;
        this.configured = configured;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownSeconds = Math.max(1, cooldownSeconds);
        this.dailyBudget = dailyBudget;
        this.effectiveModel = effectiveModel;
        this.clock = clock;
        this.day.set(clock.get());
    }

    public boolean configured() {
        return configured;
    }

    /** The provider default model this member would use (observability only). */
    public String effectiveModel() {
        return effectiveModel;
    }

    private LocalDate utcDay() {
        return clock.get();
    }

    /** Rolls the per-day request counter over when the UTC day changes. */
    private void rollDay() {
        LocalDate today = utcDay();
        LocalDate current = day.get();
        if (!today.equals(current) && day.compareAndSet(current, today)) {
            requestsToday.set(0);
        }
    }

    public void recordSuccess() {
        rollDay();
        consecutiveFailures.set(0);
        requestsToday.incrementAndGet();
    }

    /** Legacy shape — classified {@link LlmFailureClass#UNKNOWN}. */
    public void recordFailure(String message) {
        recordFailure(message, LlmFailureClass.UNKNOWN);
    }

    public void recordFailure(String message, LlmFailureClass failureClass) {
        rollDay();
        consecutiveFailures.incrementAndGet();
        requestsToday.incrementAndGet();
        lastErrorAt.set(Instant.now());
        lastErrorMessage.set(message);
        LlmFailureClass classified = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        lastFailureClass.set(classified);
        if (classified.isConfigurationFailure()) {
            // dead key / retired model cannot heal mid-deployment: skip future
            // attempts until the UTC day rolls over (ADR-023)
            cooldownUntil.set(endOfUtcDay());
        } else if (consecutiveFailures.get() >= failureThreshold) {
            cooldownUntil.set(Instant.now().plusSeconds(cooldownSeconds));
        }
    }

    private Instant endOfUtcDay() {
        return utcDay().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean inCooldown() {
        Instant until = cooldownUntil.get();
        return until != null && Instant.now().isBefore(until);
    }

    /** True when the local daily budget is configured and fully consumed. */
    public boolean budgetExhausted() {
        rollDay();
        return dailyBudget > 0 && requestsToday.get() >= dailyBudget;
    }

    /** Whether the member can serve traffic right now (ADR-023 admin health). */
    public boolean healthy() {
        return configured && !inCooldown() && !budgetExhausted();
    }

    public Snapshot snapshot() {
        rollDay();
        boolean cooling = inCooldown();
        return new Snapshot(
                configured,
                consecutiveFailures.get(),
                requestsToday.get(),
                lastErrorAt.get(),
                lastErrorMessage.get(),
                cooldownUntil.get(),
                enabled,
                healthy(),
                cooling,
                dailyBudget > 0 ? dailyBudget : null,
                dailyBudget > 0 ? Math.max(0, dailyBudget - requestsToday.get()) : null,
                lastFailureClass.get(),
                effectiveModel);
    }

    /**
     * Immutable observability view (ADR-023 admin health output). The first six
     * components are the original §26.1 contract — later components are additive
     * (backward-compatible for existing admin consumers).
     */
    public record Snapshot(
            boolean configured,
            int consecutiveFailures,
            int requestsToday,
            Instant lastErrorAt,
            String lastErrorMessage,
            Instant cooldownUntil,
            boolean enabled,
            boolean healthy,
            boolean coolingDown,
            Integer dailyBudget,
            Integer remainingLocalBudget,
            LlmFailureClass lastFailureClass,
            String effectiveModel) {
    }
}
