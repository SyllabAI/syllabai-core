package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.PasswordChangeRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.dto.UserView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authentication endpoints (Master Spec §22).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 UriComponentsBuilder uri) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.created(uri.path("/api/v1/auth/me").build().toUri())
                .body(response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        try {
            return authService.login(request);
        } catch (BadCredentialsException ex) {
            // 401 without echoing which part was wrong
            throw ex;
        }
    }

    @GetMapping("/me")
    public UserView me(Authentication authentication) {
        return authService.me(authentication.getName());
    }

    /**
     * Self-service credential rotation: requires the CURRENT password, so a
     * bearer token alone cannot take over the account. 204 on success; 401 on
     * wrong current password (no echo of which factor failed); 404 unknown
     * user; 400 bean validation on the new password.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordChangeRequest request,
                               Authentication authentication) {
        authService.changePassword(authentication.getName(), request);
    }
}
