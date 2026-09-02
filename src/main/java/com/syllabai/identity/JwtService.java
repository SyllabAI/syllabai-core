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
                      @Value("${syllabai.security.jwt-ttl:PT12H}") Duration ttl) {
        if (secret == null || secret.isBlank() || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "syllabai.security.jwt-secret must be set to at least 32 bytes (env SYLLABAI_JWT_SECRET)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.ttl = ttl;
    }

    public record TokenInfo(String subject, UUID userId, Set<Role> roles, Instant expiresAt) {
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        List<String> roles = user.roles().stream().map(Role::name).toList();
        return Jwts.builder()
                .subject(user.email())
                .id(user.id().toString())
                .claim("uid", user.id().toString())
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
        @SuppressWarnings("unchecked")
        List<String> roleNames = (List<String>) claims.getOrDefault("roles", List.of());
        Set<Role> roles = roleNames.stream().map(Role::valueOf).collect(java.util.stream.Collectors.toSet());
        return new TokenInfo(claims.getSubject(), userId, roles, claims.getExpiration().toInstant());
    }

    public Duration ttl() {
        return ttl;
    }
}
