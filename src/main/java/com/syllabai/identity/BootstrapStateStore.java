package com.syllabai.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin SQL access to the {@code bootstrap_admin_state} single row (V19). Kept
 * separate from the service so the claim/expire decision logic is unit-testable
 * without a database; the SQL surface itself is exercised by the real-host
 * rehearsal (local campaign database boots) before any deployment.
 *
 * <p>All reads that gate a claim take {@code FOR UPDATE} so two concurrent
 * claims serialize on the row; the second claimer then observes CONSUMED (or
 * the post-commit admin count) and is refused.</p>
 */
@Component
public class BootstrapStateStore {

    private final JdbcTemplate jdbc;

    public BootstrapStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public enum State { PENDING, CONSUMED, EXPIRED }

    /** Locked read of the state row (call inside the claim transaction). */
    public Optional<State> lockState() {
        return jdbc.query("SELECT state FROM bootstrap_admin_state WHERE id = 1 FOR UPDATE",
                (rs, i) -> State.valueOf(rs.getString(1))).stream().findFirst();
    }

    /** Unlocked read for status/expire checks. */
    public Optional<State> peekState() {
        return jdbc.query("SELECT state FROM bootstrap_admin_state WHERE id = 1",
                (rs, i) -> State.valueOf(rs.getString(1))).stream().findFirst();
    }

    /** Number of accounts holding the ADMIN role. */
    public int adminCount() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM user_roles WHERE role = 'ADMIN'", Integer.class);
        return n == null ? 0 : n;
    }

    /** Consume the window for a claimant; returns false when the row was not PENDING anymore. */
    public boolean consume(UUID claimedBy) {
        return jdbc.update(
                "UPDATE bootstrap_admin_state SET state = 'CONSUMED', claimed_by = ?, "
                        + "claimed_at = now(), updated_at = now() WHERE id = 1 AND state = 'PENDING'",
                claimedBy) == 1;
    }

    /** Terminal expiry of an unused window; returns false when already claimed or expired. */
    public boolean expire() {
        return jdbc.update(
                "UPDATE bootstrap_admin_state SET state = 'EXPIRED', updated_at = now() "
                        + "WHERE id = 1 AND state = 'PENDING'") == 1;
    }
}
