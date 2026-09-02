package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.dto.UserView;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.Set;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
        User user = userRepository.findByEmailIgnoreCase(request.email())
                .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (!user.enabled() || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new BadCredentialsException("invalid credentials");
        }
        return new AuthResponse(jwtService.issueAccessToken(user), null, UserView.from(user));
    }

    @Transactional(readOnly = true)
    public UserView me(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(UserView::from)
                .orElseThrow(() -> new NotFoundException("user", email));
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
