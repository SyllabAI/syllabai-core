package com.syllabai.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts the bearer token, verifies it, and establishes the request security context.
 * Invalid or missing tokens simply leave the context empty — the filter chain decides
 * what happens next (public paths proceed, protected paths get 401).
 *
 * <p>Since V46 the token is not the whole story: a signature-valid JWT is only
 * honored if its {@code ver} claim still matches {@code users.token_version}
 * and the account still exists and is enabled. This is the revocation anchor —
 * password rotation bumps the row, instantly ending every previously issued
 * session; a disabled or deleted account loses access on its next request.
 * All three mismatches fail CLOSED (context stays empty → 401 on protected
 * routes), and the lookup is one PK read on a small table — the same
 * per-request cost class the M1 rate limiter already pays.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                JwtService.TokenInfo info = jwtService.parse(header.substring(7));
                // fail-closed revocation check (R1/R2): unknown user, disabled
                // account, or stale ver all leave the context empty
                Optional<User> user = users.findById(info.userId());
                if (user.filter(u -> u.enabled()
                        && info.tokenVersion() != null
                        && u.tokenVersion() == info.tokenVersion()).isPresent()) {
                    List<SimpleGrantedAuthority> authorities = info.roles().stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                            .toList();
                    var authentication = new UsernamePasswordAuthenticationToken(
                            info.subject(), null, authorities);
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute("com.syllabai.userId", info.userId());
                }
            } catch (io.jsonwebtoken.JwtException | IllegalArgumentException ignored) {
                // Invalid token: leave context empty; protected routes will reject.
            }
        }
        chain.doFilter(request, response);
    }
}
