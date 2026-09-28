package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.dto.UserView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time first-admin bootstrap (V19 {@code bootstrap_admin_state}).
 *
 * <p>Product rule (Master Spec §6.1): self-registration always creates a
 * STUDENT and "teachers/admins are provisioned by an admin" — which on a fresh
 * deployment is a chicken-and-egg. This service closes it with the standard
 * first-account pattern, guarded to stay one-time and durably self-closing:</p>
 *
 * <ul>
 *   <li>the claim is legal ONLY while the V19 state row is PENDING and ZERO
 *       accounts hold the ADMIN role (row-locked, single transaction);</li>
 *   <li>the claim consumes the window terminally (CONSUMED) in the same
 *       transaction that creates the account;</li>
 *   <li>an unused window expires terminally (EXPIRED) {@link #CLAIM_WINDOW}
 *       after boot, so an unclaimed deployment does not stay open indefinitely;
 *       reopening is an explicit, auditable migration — never a runtime
 *       action;</li>
 *   <li>the whole surface can be disabled by env
 *       ({@code syllabai.bootstrap.enabled=false}) and never logs or returns
 *       credential material.</li>
 * </ul>
 *
 * <p>The claimant account is ADMIN+TEACHER — exactly the shape the local
 * DemoUserSeeder provisions for development — so the claiming operator can
 * both provision other teachers and administer the platform immediately.</p>
 */
@Service
public class BootstrapAdminService {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    /** How long after boot an unclaimed window stays open (then expires terminally). */
    static final Duration CLAIM_WINDOW = Duration.ofMinutes(15);

    private final BootstrapStateStore state;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final boolean enabled;

    public BootstrapAdminService(BootstrapStateStore state,
                                 UserRepository users,
                                 PasswordEncoder passwordEncoder,
                                 JwtService jwtService,
                                 @Value("${syllabai.bootstrap.enabled:true}") boolean enabled) {
        this.state = state;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.enabled = enabled;
    }

    /** Anonymous status probe: is the one-time claim still available? */
    @Transactional(readOnly = true)
    public boolean claimable() {
        if (!enabled) {
            return false;
        }
        BootstrapStateStore.State s = state.peekState().orElse(BootstrapStateStore.State.EXPIRED);
        return s == BootstrapStateStore.State.PENDING && state.adminCount() == 0;
    }

    /** The one-time claim: creates the first ADMIN+TEACHER account and consumes the window. */
    @Transactional
    public AuthResponse claim(RegisterRequest request) {
        if (!enabled) {
            throw new ConflictException("bootstrap claim surface is disabled on this deployment");
        }
        BootstrapStateStore.StateAndArmedAt armed = state.lockStateWithArmedAt()
                .orElseThrow(() -> new ConflictException("bootstrap state row missing"));
        if (armed.state() != BootstrapStateStore.State.PENDING) {
            throw new ConflictException("bootstrap claim window is closed (state " + armed.state() + ")");
        }
        // wall-clock enforcement ON the claim path (R3): the scheduled expiry
        // job can miss a window on a deployment that spins down before its
        // 15-minute tick — and anchoring to bootMillis let every cold boot
        // re-arm a fresh anonymous window. The persisted armed-at timestamp
        // makes the window genuinely one-shot: expired-at-claim flips the row
        // to EXPIRED terminally, matching the V19 contract.
        if (java.time.Instant.now().isAfter(armed.updatedAt().plus(CLAIM_WINDOW))) {
            if (state.expire()) {
                log.warn("AUDIT: bootstrap claim window EXPIRED unused — wall-clock "
                        + "check at claim time (armed at {}). Reopening requires an "
                        + "explicit migration", armed.updatedAt());
            }
            throw new ConflictException("bootstrap claim window is closed (state EXPIRED)");
        }
        if (state.adminCount() > 0) {
            throw new ConflictException("an ADMIN account already exists");
        }
        validatePasswordStrength(request.password());

        User user = new User(
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.displayName().trim(),
                Set.of(Role.ADMIN, Role.TEACHER));
        // saveAndFlush, NOT save: consume() below is a JdbcTemplate UPDATE inside
        // the SAME transaction — JdbcTemplate bypasses Hibernate write-behind, so
        // the users INSERT must be flushed to the DB first or the claimed_by FK
        // fails (DataIntegrityViolation → 500) on a genuinely fresh deployment.
        user = users.saveAndFlush(user);

        if (!state.consume(user.id())) {
            // concurrent claim won the row race — refuse (transaction rolls back)
            throw new ConflictException("bootstrap claim window is closed (state CONSUMED)");
        }
        log.warn("AUDIT: first-admin bootstrap claimed by {} at {} — window consumed terminally",
                request.email().toLowerCase(), java.time.Instant.now());
        return new AuthResponse(jwtService.issueAccessToken(user), null, UserView.from(user));
    }

    /** Terminal expiry of an unused window — bounded exposure even if never claimed.
     *  Anchored to the row's persisted armed-at time (R3), not JVM boot: the
     *  job keeps working across restarts instead of re-arming the window. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void expireUnusedWindow() {
        if (!enabled) {
            return;
        }
        try {
            BootstrapStateStore.StateAndArmedAt armed = state.peekStateWithArmedAt()
                    .orElse(null);
            if (armed != null
                    && armed.state() == BootstrapStateStore.State.PENDING
                    && state.adminCount() == 0
                    && java.time.Instant.now().isAfter(armed.updatedAt().plus(CLAIM_WINDOW))) {
                if (state.expire()) {
                    log.warn("AUDIT: bootstrap claim window EXPIRED unused after {} — reopening "
                            + "requires an explicit migration", CLAIM_WINDOW);
                }
            }
        } catch (RuntimeException e) {
            // never crash the scheduler on a transient DB hiccup; next tick retries
            log.debug("bootstrap expiry check skipped: {}", e.toString());
        }
    }

    /** Bootstrap accounts carry production credentials: require real password strength. */
    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 12
                || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw new BadRequestException(
                    "bootstrap password must be at least 12 characters and contain letters "
                            + "and digits");
        }
    }
}
