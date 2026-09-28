package com.syllabai.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The per-TARGET-ACCOUNT login budget (R5, second half): the source-IP auth
 * tier is bypassable on Render (the proxy forwards client-supplied XFF
 * verbatim and adds only internal hops — live-probed), so the bound that
 * survives source spoofing is keyed on the account being attacked. Fixed
 * windows, per-email isolation, success-clears-history, disabled switch.
 */
class LoginAttemptBudgetTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-28T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RateLimitProperties props = new RateLimitProperties(
            true, Duration.ofSeconds(60), 10, 5, 3, 10, 20, 3);
    private final LoginAttemptBudget budget = new LoginAttemptBudget(props, clock);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("exhausting the per-account budget throws 429-shaped RateLimitException")
    void exhaustsPerAccount() {
        for (int i = 0; i < 3; i++) {
            budget.recordFailure("victim@syllabai.dev");
        }
        assertThatThrownBy(() -> budget.checkAllowed("victim@syllabai.dev"))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("Too many attempts");
    }

    @Test
    @DisplayName("budgets are per TARGET account — other accounts unaffected")
    void perAccountIsolation() {
        budget.recordFailure("victim@syllabai.dev");
        budget.recordFailure("victim@syllabai.dev");
        budget.recordFailure("victim@syllabai.dev");
        assertThatCode(() -> budget.checkAllowed("other@syllabai.dev"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the window resets — the account becomes reachable again")
    void windowReset() {
        for (int i = 0; i < 3; i++) {
            budget.recordFailure("victim@syllabai.dev");
        }
        assertThatThrownBy(() -> budget.checkAllowed("victim@syllabai.dev"))
                .isInstanceOf(RateLimitException.class);
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThatCode(() -> budget.checkAllowed("victim@syllabai.dev"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a successful login clears the account's history mid-window")
    void successClearsHistory() {
        budget.recordFailure("victim@syllabai.dev");
        budget.recordFailure("victim@syllabai.dev");
        budget.recordSuccess("victim@syllabai.dev");
        assertThat(budget.currentCount("victim@syllabai.dev")).isZero();
        assertThatCode(() -> budget.checkAllowed("victim@syllabai.dev"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the enabled switch disables the budget entirely (IT profile)")
    void disabledSwitch() {
        RateLimitProperties off = new RateLimitProperties(
                false, Duration.ofSeconds(60), 10, 5, 3, 10, 20, 3);
        LoginAttemptBudget disabled = new LoginAttemptBudget(off, clock);
        for (int i = 0; i < 50; i++) {
            disabled.recordFailure("victim@syllabai.dev");
        }
        assertThatCode(() -> disabled.checkAllowed("victim@syllabai.dev"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("key normalization: case and whitespace variants hit one bucket")
    void keyNormalization() {
        budget.recordFailure("Victim@Syllabai.dev ");
        budget.recordFailure("  victim@syllabai.dev");
        budget.recordFailure("VICTIM@syllabai.dev");
        assertThat(budget.currentCount("victim@syllabai.dev")).isEqualTo(3);
        assertThatThrownBy(() -> budget.checkAllowed("victim@syllabai.dev"))
                .isInstanceOf(RateLimitException.class);
    }
}
