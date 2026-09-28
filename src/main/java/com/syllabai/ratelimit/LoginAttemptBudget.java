package com.syllabai.ratelimit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-TARGET-ACCOUNT budget for login attempts (deep-audit re-derivation R5,
 * second half). Live probing established that on Render the app cannot derive
 * a trustworthy per-client IP: the proxy forwards the client's own
 * X-Forwarded-For verbatim (attacker-controlled) plus only internal hops —
 * so the filter-level per-IP auth tier is best-effort and bypassable by an
 * attacker who rotates fake XFF values. The bound that survives source
 * spoofing is the account being attacked: failed logins are counted PER
 * EMAIL here, at the service layer, before any password verification work.
 *
 * <p>Shape mirrors the M1 filter: in-memory fixed windows on a Clock,
 * single-instance semantics, size-guarded map, {@code enabled} from the same
 * {@code syllabai.ratelimit.enabled} switch. A SUCCESSFUL login clears the
 * account's counter — a legitimate user mistyping twice is never locked out
 * by their own history; an attacker hammering one account hits the budget
 * after {@code loginPerAccount} failures per window regardless of how many
 * source identities they rotate through.</p>
 *
 * <p>Deliberate tradeoff (documented, not silent): a hostile party CAN
 * exhaust a victim's per-account budget by sending garbage logins for the
 * victim's address (victim sees 429 for one window). That DoS shape is the
 * standard cost of per-account limiting (OWASP credential-stuffing guidance)
 * and is strictly smaller than the unbounded brute-force it prevents;
 * BCrypt keeps each counted attempt expensive for the attacker too.</p>
 */
@Component
public class LoginAttemptBudget {

    /** Guard against unbounded key growth (mirror of the M1 MAX_KEYS sweep). */
    static final int MAX_KEYS = 100_000;

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public LoginAttemptBudget(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** test-visible clock: window expiry is exercised without sleeping */
    LoginAttemptBudget(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    private record Window(Instant start, int count) { }

    /**
     * @throws RateLimitException when this account's failed-login budget for
     *         the current window is exhausted
     */
    public void checkAllowed(String email) {
        if (!properties.enabled() || email == null || email.isBlank()) {
            return;
        }
        Window w = failures.get(key(email));
        if (w != null && insideWindow(w) && w.count() >= properties.loginPerAccount()) {
            throw new RateLimitException(retryAfterSeconds(w));
        }
    }

    /** Count one FAILED attempt against the account. */
    public void recordFailure(String email) {
        if (!properties.enabled() || email == null || email.isBlank()) {
            return;
        }
        String key = key(email);
        Instant now = clock.instant();
        long windowMillis = properties.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli((now.toEpochMilli() / windowMillis) * windowMillis);
        failures.compute(key, (k, existing) ->
                existing == null || !insideWindow(existing)
                        ? new Window(windowStart, 1)
                        : new Window(existing.start(), existing.count() + 1));
        if (failures.size() > MAX_KEYS) {
            failures.values().removeIf(w -> !insideWindow(w));
        }
    }

    /** A successful login clears the account's history — legitimate users never accumulate. */
    public void recordSuccess(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        failures.remove(key(email));
    }

    private boolean insideWindow(Window w) {
        return !clock.instant().isAfter(w.start().plus(properties.window()));
    }

    private int retryAfterSeconds(Window w) {
        long windowMillis = properties.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli((clock.instant().toEpochMilli() / windowMillis) * windowMillis);
        // the current window for THIS key may have started earlier than the
        // aligned one if the key was recorded in the previous window and is
        // still inside the (single-window) semantics — keep it simple and
        // bound the wait by the configured window either way
        long remaining = w.start().plus(properties.window()).toEpochMilli() - clock.instant().toEpochMilli();
        long aligned = windowStart.plusMillis(windowMillis).toEpochMilli() - clock.instant().toEpochMilli();
        return (int) Math.max(1, Math.min(remaining, aligned) / 1000 + 1);
    }

    private static String key(String email) {
        return email.strip().toLowerCase();
    }

    /** test-visible current count for a key */
    int currentCount(String email) {
        Window w = failures.get(key(email));
        return w == null || !insideWindow(w) ? 0 : w.count();
    }
}
