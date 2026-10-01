package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.PasswordChangeRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.dto.UserView;
import com.syllabai.ratelimit.LoginAttemptBudget;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.ForbiddenException;
import com.syllabai.shared.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptBudget loginBudget;
    /**
     * The teacher join code (syllabai.security.teacher-join-code / env
     * SYLLABAI_TEACHER_JOIN_CODE). Blank/absent = the teacher self-service
     * path is CLOSED — every teacher signup attempt is refused, the same 403
     * a wrong code gets (no configuration oracle).
     */
    private final String teacherJoinCode;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginAttemptBudget loginBudget,
                       @Value("${syllabai.security.teacher-join-code:}") String teacherJoinCode) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginBudget = loginBudget;
        this.teacherJoinCode = teacherJoinCode == null ? "" : teacherJoinCode.trim();
    }

    /**
     * Self-registration. Master Spec §6.1, as amended by the teacher-join-code
     * ruling (09-29): the default self-service role is STUDENT; a requester
     * may register as TEACHER only by presenting the platform's teacher join
     * code, and the gate FAILS CLOSED — an unset or blank configured code
     * refuses every teacher signup with the same 403 a wrong code earns (a
     * probing client learns nothing about the operator's configuration). The
     * comparison is constant-time. ADMIN is never self-serviceable — the
     * bootstrap path owns that role. Registration remains budget-gated at the
     * filter tier, so the join code is not the only bound on brute force.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("email already registered");
        }
        Role role = selfServiceRole(request.role());
        if (role == Role.TEACHER) {
            requireJoinCode(request.joinCode());
        }
        User user = new User(
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                Set.of(role));
        user = userRepository.save(user);
        if (role == Role.TEACHER) {
            log.info("AUDIT: teacher self-registered via join code ({})",
                    request.email().toLowerCase());
        }
        return new AuthResponse(jwtService.issueAccessToken(user), null, UserView.from(user));
    }

    /**
     * STUDENT is the default self-service role; ADMIN is never
     * self-serviceable (fail-closed 403, not a silent downgrade to STUDENT);
     * an unrecognized role is an honest 400.
     */
    private Role selfServiceRole(String requested) {
        if (requested == null || requested.isBlank()) {
            return Role.STUDENT;
        }
        Role role;
        try {
            role = Role.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("unknown role: " + requested.trim());
        }
        if (role == Role.ADMIN) {
            throw new ForbiddenException("admin accounts are provisioned by an administrator");
        }
        return role;
    }

    /**
     * The fail-closed join-code gate: unset/blank configured code refuses
     * everything; a presented code is compared in constant time and the SAME
     * 403 is returned for every failure shape.
     */
    private void requireJoinCode(String presented) {
        if (teacherJoinCode.isBlank()
                || presented == null
                || !MessageDigest.isEqual(
                        teacherJoinCode.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("teacher registration requires a valid join code");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // per-TARGET-account budget (R5): the source-IP tier is bypassable on
        // Render (the proxy forwards client-supplied XFF verbatim), so the
        // bound that survives source spoofing is keyed on the account being
        // attacked. Checked BEFORE any bcrypt work; success clears history.
        loginBudget.checkAllowed(request.email());
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElse(null);
        if (user == null
                || !user.enabled()
                || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            loginBudget.recordFailure(request.email());
            throw new BadCredentialsException("invalid credentials");
        }
        loginBudget.recordSuccess(request.email());
        return new AuthResponse(jwtService.issueAccessToken(user), null, UserView.from(user));
    }

    @Transactional(readOnly = true)
    public UserView me(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(UserView::from)
                .orElseThrow(() -> new NotFoundException("user", email));
    }

    /**
     * Self-service credential rotation (§22): the caller must present the
     * CURRENT password — knowledge of the existing secret authorizes the
     * change, so a stolen bearer token alone cannot take over the account.
     * Same strength rule as registration (12+ chars, letters+digits,
     * bean-validated at the web boundary). AUDIT-logged: every rotation is
     * an identity event.
     *
     * <p>Rotation also bumps {@code users.token_version} (R1): every token
     * issued before the rotation is dead on its next request — including the
     * rotating client's own. Re-login is the documented contract.</p>
     */
    @Transactional
    public void changePassword(String email, PasswordChangeRequest request) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("user", email));
        if (!user.enabled()
                || !passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }
        user.rotatePasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("AUDIT: password rotated (self-service) for {}", email);
    }

    @Transactional
    public User provisionUser(String email, String rawPassword, String displayName, Set<Role> roles) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("email already registered");
        }
        return userRepository.save(new User(
                email.toLowerCase(),
                passwordEncoder.encode(rawPassword),
                displayName,
                roles));
    }
}
