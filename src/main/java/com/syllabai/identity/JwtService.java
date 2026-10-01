package com.syllabai.identity;

import com.syllabai.shared.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>The signing key comes from {@code syllabai.security.jwt-secret} (env
 * {@code SYLLABAI_JWT_SECRET}). A blank secret fails fast at startup — except in
 * tests, which inject their own key. Secrets must be at least 256 bits (32 bytes).</p>
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${syllabai.security.jwt-secret:}") String secret,
                      // PT2H (audit re-derivation residual promoted 09-28): revocation
                      // via token_version (R1) already kills a stolen token at its next
                      // API call, but the TTL still bounds the copied-token window for
                      // credentials lifted from client storage and never reused. 2h
                      // keeps a full study session intact (the web client's 401 flow
                      // is a graceful re-login) while cutting the 12h window 6x.
                      @Value("${syllabai.security.jwt-ttl:PT2H}") Duration ttl) {
        if (secret == null || secret.isBlank() || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "syllabai.security.jwt-secret must be set to at least 32 bytes (env SYLLABAI_JWT_SECRET)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.ttl = ttl;
    }

    public record TokenInfo(String subject, UUID userId, Set<Role> roles,
                            Long tokenVersion, Instant expiresAt) {
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        List<String> roles = user.roles().stream().map(Role::name).toList();
        return Jwts.builder()
                .subject(user.email())
                .id(user.id().toString())
                .claim("uid", user.id().toString())
                // revocation epoch (R1): the filter compares this against
                // users.token_version per request — a bump kills the token
                .claim("ver", user.tokenVersion())
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException when the token is invalid or expired */
    public TokenInfo parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        UUID userId = UUID.fromString(claims.get("uid", String.class));
        // absent ver (pre-V46 token) parses as null — the filter treats a
        // null version as mismatched (fail-closed; see V46 deploy note)
        Long tokenVersion = claims.get("ver", Long.class);
        @SuppressWarnings("unchecked")
        List<String> roleNames = (List<String>) claims.getOrDefault("roles", List.of());
        Set<Role> roles = roleNames.stream().map(Role::valueOf).collect(java.util.stream.Collectors.toSet());
        return new TokenInfo(claims.getSubject(), userId, roles, tokenVersion,
                claims.getExpiration().toInstant());
    }

    public Duration ttl() {
        return ttl;
    }
}
