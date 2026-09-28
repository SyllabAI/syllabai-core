package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.PasswordChangeRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.dto.UserView;
import com.syllabai.ratelimit.LoginAttemptBudget;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       LoginAttemptBudget loginBudget) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginBudget = loginBudget;
    }

    /**
     * Self-registration always creates a STUDENT (Master Spec §6.1).
     * Teachers/admins are provisioned by an admin.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("email already registered");
        }
        User user = new User(
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.displayName(),
                Set.of(Role.STUDENT));
        user = userRepository.save(user);
        return new AuthResponse(jwtService.issueAccessToken(user), null, UserView.from(user));
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
