package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-023 daily-budget semantics on the real health tracker: the local budget is a
 * routing guard (requestsToday >= budget ⇒ ineligible until UTC day rollover), and
 * configuration failures (dead key / retired model) suppress future attempts until
 * the end of the UTC day while transient failures keep threshold/cooldown semantics.
 */
class LlmProviderHealthTest {

    /** Mutable "today" — the UTC-rollover test clock. */
    private final AtomicReference<LocalDate> today =
            new AtomicReference<>(LocalDate.now(ZoneOffset.UTC));

    private LlmProviderHealth health(boolean configured, int threshold, int cooldown,
                                     int budget, String model) {
        Supplier<LocalDate> clock = today::get;
        return new LlmProviderHealth(configured, configured, threshold, cooldown, budget,
                model, clock);
    }

    // ── budget boundaries ───────────────────────────────────────────────────────

    @Test
    @DisplayName("0 of budget used → eligible")
    void zeroUsedIsEligible() {
        LlmProviderHealth h = health(true, 3, 60, 5, "m");
        assertThat(h.budgetExhausted()).isFalse();
        assertThat(h.snapshot().remainingLocalBudget()).isEqualTo(5);
    }

    @Test
    @DisplayName("budget − 1 used → eligible; budget used → ineligible; budget + N → ineligible")
    void budgetBoundaries() {
        LlmProviderHealth h = health(true, 3, 60, 5, "m");
        for (int i = 0; i < 4; i++) {
            h.recordSuccess();          // 4 = budget − 1
        }
        assertThat(h.budgetExhausted()).isFalse();
        assertThat(h.snapshot().remainingLocalBudget()).isEqualTo(1);

        h.recordSuccess();              // 5 = budget
        assertThat(h.budgetExhausted()).isTrue();
        assertThat(h.snapshot().remainingLocalBudget()).isZero();

        h.recordSuccess();              // 6 = budget + 1 (attempt still counted)
        h.recordSuccess();              // 7 = budget + N
        assertThat(h.budgetExhausted()).isTrue();
        assertThat(h.snapshot().requestsToday()).isEqualTo(7);
    }

    @Test
    @DisplayName("UTC day rollover makes the provider eligible again and resets the counter")
    void utcDayRolloverRestoresEligibility() {
        LlmProviderHealth h = health(true, 3, 60, 5, "m");
        for (int i = 0; i < 5; i++) {
            h.recordSuccess();
        }
        assertThat(h.budgetExhausted()).isTrue();

        today.set(today.get().plusDays(1));   // UTC midnight passes

        assertThat(h.budgetExhausted()).isFalse();
        assertThat(h.snapshot().requestsToday()).isZero();
        assertThat(h.snapshot().remainingLocalBudget()).isEqualTo(5);
    }

    @Test
    @DisplayName("no budget configured (0) → never exhausted, remaining is null (unlimited)")
    void unlimitedBudget() {
        LlmProviderHealth h = health(true, 3, 60, 0, "m");
        for (int i = 0; i < 50; i++) {
            h.recordSuccess();
        }
        assertThat(h.budgetExhausted()).isFalse();
        assertThat(h.snapshot().dailyBudget()).isNull();
        assertThat(h.snapshot().remainingLocalBudget()).isNull();
    }

    @Test
    @DisplayName("failed attempts count against the local budget too")
    void failuresCountTowardBudget() {
        LlmProviderHealth h = health(true, 100, 60, 3, "m");
        h.recordFailure("x", LlmFailureClass.RATE_LIMITED);
        h.recordFailure("y", LlmFailureClass.RATE_LIMITED);
        h.recordFailure("z", LlmFailureClass.RATE_LIMITED);
        assertThat(h.budgetExhausted()).isTrue();
    }

    // ── configuration vs transient failure semantics ───────────────────────────

    @Test
    @DisplayName("AUTHENTICATION_FAILURE suppresses the provider immediately, until end of UTC day")
    void authFailureSuppressesUntilDayEnd() {
        LlmProviderHealth h = health(true, 3, 60, 0, "m");
        assertThat(h.inCooldown()).isFalse();

        h.recordFailure("401 unauthorized", LlmFailureClass.AUTHENTICATION_FAILURE);

        // a single failure is enough — no threshold needed for a dead key
        assertThat(h.inCooldown()).isTrue();
        Instant endOfDay = today.get().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(h.snapshot().cooldownUntil()).isEqualTo(endOfDay);
        assertThat(h.snapshot().lastFailureClass()).isEqualTo(LlmFailureClass.AUTHENTICATION_FAILURE);
    }

    @Test
    @DisplayName("MODEL_NOT_FOUND suppresses the provider (configuration defect, not an outage)")
    void modelNotFoundSuppresses() {
        LlmProviderHealth h = health(true, 3, 60, 0, "m");
        h.recordFailure("404 model retired", LlmFailureClass.MODEL_NOT_FOUND);
        assertThat(h.inCooldown()).isTrue();
        assertThat(h.snapshot().lastFailureClass()).isEqualTo(LlmFailureClass.MODEL_NOT_FOUND);
    }

    @Test
    @DisplayName("transient failures keep the threshold/cooldown semantics")
    void transientFailuresKeepThreshold() {
        LlmProviderHealth h = health(true, 3, 60, 0, "m");
        h.recordFailure("429", LlmFailureClass.RATE_LIMITED);
        h.recordFailure("503", LlmFailureClass.PROVIDER_UNAVAILABLE);
        assertThat(h.inCooldown()).isFalse();      // below threshold
        h.recordFailure("boom", LlmFailureClass.UNKNOWN);
        assertThat(h.inCooldown()).isTrue();       // 3 consecutive → cooldown
        assertThat(h.snapshot().lastFailureClass()).isEqualTo(LlmFailureClass.UNKNOWN);
    }

    // ── observability snapshot ─────────────────────────────────────────────────

    @Test
    @DisplayName("snapshot exposes the ADR-023 admin health fields")
    void snapshotFields() {
        LlmProviderHealth h = health(true, 3, 60, 10, "openai/gpt-oss-120b");
        LlmProviderHealth.Snapshot s = h.snapshot();
        assertThat(s.configured()).isTrue();
        assertThat(s.enabled()).isTrue();
        assertThat(s.healthy()).isTrue();
        assertThat(s.coolingDown()).isFalse();
        assertThat(s.dailyBudget()).isEqualTo(10);
        assertThat(s.remainingLocalBudget()).isEqualTo(10);
        assertThat(s.lastFailureClass()).isNull();
        assertThat(s.effectiveModel()).isEqualTo("openai/gpt-oss-120b");

        h.recordFailure("403", LlmFailureClass.AUTHENTICATION_FAILURE);
        LlmProviderHealth.Snapshot after = h.snapshot();
        assertThat(after.healthy()).isFalse();
        assertThat(after.coolingDown()).isTrue();
        assertThat(after.lastFailureClass()).isEqualTo(LlmFailureClass.AUTHENTICATION_FAILURE);
    }

    @Test
    @DisplayName("healthy is false when configured=false even without failures")
    void unconfiguredIsUnhealthy() {
        Supplier<LocalDate> clock = today::get;
        LlmProviderHealth h = new LlmProviderHealth(true, false, 3, 60, 10, "m", clock);
        LlmProviderHealth.Snapshot s = h.snapshot();
        assertThat(s.enabled()).isTrue();      // enabled in config…
        assertThat(s.configured()).isFalse();  // …but key missing — the drift signal
        assertThat(s.healthy()).isFalse();
    }
}
