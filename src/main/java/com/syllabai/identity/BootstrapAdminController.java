package com.syllabai.identity;

import com.syllabai.identity.dto.AuthResponse;
import com.syllabai.identity.dto.RegisterRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one-time first-admin bootstrap surface (see {@link BootstrapAdminService}
 * for the gate semantics). Anonymous by design — the gate is the V19 state row
 * plus the zero-admins invariant, not authentication (an unauthenticated caller
 * IS the first operator of a fresh deployment). Both endpoints are honest about
 * the window: the status probe exists so the claim can happen immediately after
 * a deploy instead of relying on obscurity.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class BootstrapAdminController {

    private final BootstrapAdminService bootstrap;

    public BootstrapAdminController(BootstrapAdminService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @GetMapping("/bootstrap-status")
    public Map<String, Boolean> status() {
        return Map.of("available", bootstrap.claimable());
    }

    @PostMapping("/bootstrap-admin")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse claim(@Valid @RequestBody RegisterRequest request) {
        return bootstrap.claim(request);
    }
}
