package com.syllabai.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The V46 revocation anchor (deep-audit R1/R2): a signature-valid token is
 * honored ONLY while its ver claim matches users.token_version and the
 * account still exists and is enabled. Every mismatch fails CLOSED — the
 * security context stays empty and protected routes 401. This is what makes
 * password rotation and account disablement actually end live sessions on a
 * stateless-JWT design that previously had no kill switch at all.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";

    @Mock
    private UserRepository users;
    @Mock
    private jakarta.servlet.FilterChain chain;

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, java.time.Duration.ofHours(12));
        user = new User("learner@syllabai.dev", "bcrypt-hash", "Learner", Set.of(Role.STUDENT));
        // the entity id is Hibernate-assigned; unit scope assigns it directly
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        token = jwtService.issueAccessToken(user);
        filter = new JwtAuthenticationFilter(jwtService, users);
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/learners/me");
        request.addHeader("Authorization", "Bearer " + bearer);
        return request;
    }

    private boolean authenticated() {
        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    private MockHttpServletResponse fire(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
    @Test
    @DisplayName("a valid token for an existing enabled user with matching ver authenticates")
    void validTokenAuthenticates() throws Exception {
        when(users.findById(any())).thenReturn(Optional.of(user));
        fire(request(token));
        assertThat(authenticated()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_STUDENT");
    }

    @Test
    @DisplayName("a token whose ver no longer matches the row is REJECTED (rotation kills it)")
    void staleVerFailsClosed() throws Exception {
        user.rotatePasswordHash("new-hash"); // bumps tokenVersion on the row
        when(users.findById(any())).thenReturn(Optional.of(user));
        fire(request(token));
        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("a token for a disabled account is REJECTED (the investigate/kill path works)")
    void disabledAccountFailsClosed() throws Exception {
        ReflectionTestUtils.setField(user, "enabled", false);
        when(users.findById(any())).thenReturn(Optional.of(user));
        fire(request(token));
        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("a token for a deleted account is REJECTED (fail-closed, no NPE)")
    void deletedAccountFailsClosed() throws Exception {
        when(users.findById(any())).thenReturn(Optional.empty());
        fire(request(token));
        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("a token with NO ver claim (pre-V46 issue) is REJECTED — deploy logs everyone out once")
    void missingVerFailsClosed() throws Exception {
        // hand-mint a ver-less token from the same key: exactly what every
        // token issued before the V46 deploy looks like
        String legacy = io.jsonwebtoken.Jwts.builder()
                .subject("learner@syllabai.dev")
                .id(user.id().toString())
                .claim("uid", user.id().toString())
                .claim("roles", java.util.List.of("STUDENT"))
                .issuedAt(new java.util.Date())
                .expiration(java.util.Date.from(java.time.Instant.now()
                        .plus(java.time.Duration.ofHours(1))))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();
        when(users.findById(any())).thenReturn(Optional.of(user));
        fire(request(legacy));
        assertThat(authenticated()).isFalse();
    }

    @Test
    @DisplayName("the chain continues even when the token is rejected")
    void chainAlwaysContinues() throws Exception {
        when(users.findById(any())).thenReturn(Optional.empty());
        MockHttpServletRequest request = request(token);
        MockHttpServletResponse response = fire(request);
        verify(chain).doFilter(request, response);
    }
}
