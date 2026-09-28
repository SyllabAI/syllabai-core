package com.syllabai.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Rate limiting (deep-audit 09-28 M1): auth tier per client IP, LLM tier per
 * learner — fixed-window budgets, 429 + Retry-After on overflow, XFF-aware
 * client IP, window reset, disabled flag, fail-open on internal error.
 */
class RateLimitFilterTest {

    /** stepnable clock for window-reset tests */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-28T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // login 2, register 2, bootstrap 1, password 2, llm 3 — tiny budgets
    private final MutableClock clock = new MutableClock();
    private final RateLimitProperties props = new RateLimitProperties(
            true, Duration.ofSeconds(60), 2, 2, 1, 2, 3);
    private final RateLimitFilter filter = new RateLimitFilter(props, clock);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest post(String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private MockHttpServletResponse fire(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("login burst beyond the per-IP budget is rejected with 429 + Retry-After (M1)")
    void loginBurstRejected() throws Exception {
        assertThat(fire(post("/api/v1/auth/login", "1.2.3.4")).getStatus()).isEqualTo(200);
        assertThat(fire(post("/api/v1/auth/login", "1.2.3.4")).getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = fire(post("/api/v1/auth/login", "1.2.3.4"));
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotNull();
        int retryAfter = Integer.parseInt(rejected.getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1, 60);
        assertThat(rejected.getContentAsString()).contains("Too Many Requests");
        assertThat(rejected.getContentAsString()).contains("retryAfterSeconds");
    }

    @Test
    @DisplayName("auth routes bucket independently per route and per IP")
    void authBucketsIndependent() throws Exception {
        // same IP: login budget exhausted, register budget untouched
        fire(post("/api/v1/auth/login", "1.2.3.4"));
        fire(post("/api/v1/auth/login", "1.2.3.4"));
        assertThat(fire(post("/api/v1/auth/login", "1.2.3.4")).getStatus()).isEqualTo(429);
        assertThat(fire(post("/api/v1/auth/register", "1.2.3.4")).getStatus()).isEqualTo(200);
        // different IP: login budget untouched
        assertThat(fire(post("/api/v1/auth/login", "5.6.7.8")).getStatus()).isEqualTo(200);
        // bootstrap is its own (tightest) bucket
        assertThat(fire(post("/api/v1/auth/bootstrap-admin", "1.2.3.4")).getStatus()).isEqualTo(200);
        assertThat(fire(post("/api/v1/auth/bootstrap-admin", "1.2.3.4")).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("X-Forwarded-For RIGHTMOST hop is the client key — the leftmost is "
            + "attacker-controlled (live probe: Render APPENDS, rotating a fake "
            + "leftmost must not mint fresh budgets)")
    void xffRightMostHopKeysTheBucket() throws Exception {
        // same topology the live probe exploited: fake leftmost, real client rightmost
        MockHttpServletRequest a = post("/api/v1/auth/login", "127.0.0.1");
        a.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        MockHttpServletRequest b = post("/api/v1/auth/login", "127.0.0.1");
        b.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.7");
        assertThat(fire(a).getStatus()).isEqualTo(200);
        assertThat(fire(a).getStatus()).isEqualTo(200);
        // same REAL client (rightmost) despite the rotated fake leftmost -> exhausted
        assertThat(fire(a).getStatus()).isEqualTo(429);
        // a different fake leftmost no longer mints a fresh budget: b's rightmost
        // is the same 203.0.113.7 -> same bucket -> still 429
        assertThat(fire(b).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("a genuinely different rightmost client gets its own budget")
    void xffDistinctRightMostClientsBucketIndependently() throws Exception {
        MockHttpServletRequest a = post("/api/v1/auth/login", "127.0.0.1");
        a.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        MockHttpServletRequest b = post("/api/v1/auth/login", "127.0.0.1");
        b.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.9");
        assertThat(fire(a).getStatus()).isEqualTo(200);
        assertThat(fire(a).getStatus()).isEqualTo(200);
        assertThat(fire(a).getStatus()).isEqualTo(429);
        assertThat(fire(b).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a private rightmost hop falls back to the socket peer — internal "
            + "callers cannot spoof fresh budgets with reserved addresses")
    void xffPrivateRightMostFallsBackToRemoteAddr() throws Exception {
        // both requests carry a private rightmost -> both key on 127.0.0.1
        MockHttpServletRequest a = post("/api/v1/auth/login", "127.0.0.1");
        a.addHeader("X-Forwarded-For", "203.0.113.7, 192.168.5.5");
        MockHttpServletRequest b = post("/api/v1/auth/login", "127.0.0.1");
        b.addHeader("X-Forwarded-For", "198.51.100.9, 10.255.255.5");
        assertThat(fire(a).getStatus()).isEqualTo(200);
        assertThat(fire(a).getStatus()).isEqualTo(200);
        assertThat(fire(a).getStatus()).isEqualTo(429);
        // b's rightmost is private -> key = 127.0.0.1 = a's bucket -> still 429
        assertThat(fire(b).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("the window resets after the budget window passes")
    void windowResetRestoresBudget() throws Exception {
        fire(post("/api/v1/auth/login", "1.2.3.4"));
        fire(post("/api/v1/auth/login", "1.2.3.4"));
        assertThat(fire(post("/api/v1/auth/login", "1.2.3.4")).getStatus()).isEqualTo(429);
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThat(fire(post("/api/v1/auth/login", "1.2.3.4")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("LLM tier keys on the JWT learner id: tutor and cla share one per-learner budget")
    void llmTierKeysOnLearner() throws Exception {
        MockHttpServletRequest tutor = post("/api/v1/tutor/ask", "1.2.3.4");
        tutor.setAttribute("com.syllabai.userId", "11111111-1111-1111-1111-111111111111");
        MockHttpServletRequest cla = post("/api/v1/learners/me/cla/ask", "5.5.5.5");
        cla.setAttribute("com.syllabai.userId", "11111111-1111-1111-1111-111111111111");

        assertThat(fire(tutor).getStatus()).isEqualTo(200);
        assertThat(fire(tutor).getStatus()).isEqualTo(200);
        assertThat(fire(cla).getStatus()).isEqualTo(200);
        // 4th ask for the SAME learner from ANY route/host -> 429
        assertThat(fire(cla).getStatus()).isEqualTo(429);
        // a different learner is untouched
        MockHttpServletRequest other = post("/api/v1/tutor/ask", "1.2.3.4");
        other.setAttribute("com.syllabai.userId", "22222222-2222-2222-2222-222222222222");
        assertThat(fire(other).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("LLM tier falls back to the authenticated subject, then the client IP")
    void llmTierFallbackKeys() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("learner@example.com", null, List.of()));
        for (int i = 0; i < 3; i++) {
            assertThat(fire(post("/api/v1/tutor/ask", "7.7.7.7")).getStatus()).isEqualTo(200);
        }
        // subject-keyed: a different host for the same principal is still over budget
        assertThat(fire(post("/api/v1/tutor/ask", "8.8.8.8")).getStatus()).isEqualTo(429);

        // no attribute, no authentication -> IP-keyed fallback
        SecurityContextHolder.clearContext();
        assertThat(fire(post("/api/v1/tutor/ask", "9.9.9.9")).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Smart Mark LLM surfaces share the per-learner LLM budget (R8: "
            + "smart-mark runs the marking pipeline once per PART)")
    void smartMarkRoutesShareTheLlmBudget() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("learner@example.com", null, List.of()));
        // this suite's tiny budget (llm=3) shared across ALL LLM surfaces:
        // 2 tutor asks + 1 smart-mark = 3 admitted
        assertThat(fire(post("/api/v1/tutor/ask", "7.7.7.7")).getStatus()).isEqualTo(200);
        assertThat(fire(post("/api/v1/tutor/ask", "7.7.7.7")).getStatus()).isEqualTo(200);
        assertThat(fire(post("/api/v1/learners/me/attempts/11111111-1111-1111-1111-111111111111"
                + "/smart-mark", "7.7.7.7")).getStatus()).isEqualTo(200);
        // 4th LLM call this window — over the shared per-learner budget
        assertThat(fire(post("/api/v1/learners/me/attempts/11111111-1111-1111-1111-111111111111"
                + "/parts/22222222-2222-2222-2222-222222222222/feedback-explanation",
                "7.7.7.7")).getStatus()).isEqualTo(429);
        assertThat(fire(post("/api/v1/learners/me/attempts/11111111-1111-1111-1111-111111111111"
                + "/parts/22222222-2222-2222-2222-222222222222/improvement-plan",
                "7.7.7.7")).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("non-matching requests are never throttled: GET ask, unlisted paths, OPTIONS, disabled filter")
    void nonMatchingRequestsPassThrough() throws Exception {
        // GET on an LLM route carries no token cost
        for (int i = 0; i < 6; i++) {
            MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/tutor/ask");
            get.setRequestURI("/api/v1/tutor/ask");
            get.setRemoteAddr("1.2.3.4");
            assertThat(fire(get).getStatus()).isEqualTo(200);
        }
        // unlisted POST path
        assertThat(fire(post("/api/v1/auth/me", "1.2.3.4")).getStatus()).isEqualTo(200);
        // CORS preflight is always exempt
        MockHttpServletRequest options = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        options.setRequestURI("/api/v1/auth/login");
        assertThat(fire(options).getStatus()).isEqualTo(200);
        // master switch off -> nothing counts
        RateLimitFilter disabled = new RateLimitFilter(
                new RateLimitProperties(false, Duration.ofSeconds(60), 1, 1, 1, 1, 1), clock);
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            disabled.doFilter(post("/api/v1/auth/login", "1.2.3.4"), response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("an internal limiter error fails OPEN — the chain continues (availability control)")
    void internalErrorFailsOpen() throws Exception {
        RateLimitFilter broken = new RateLimitFilter(props, clock) {
            @Override
            Budget budgetOf(HttpServletRequest request) {
                throw new IllegalStateException("simulated limiter fault");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        assertThatCode(() -> broken.doFilter(post("/api/v1/tutor/ask", "1.2.3.4"), response, chain))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("properties default to the audited budgets when unset")
    void propertyDefaults() {
        RateLimitProperties defaults = new RateLimitProperties(null, null, null, null, null, null, null);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.window()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.loginPerIp()).isEqualTo(10);
        assertThat(defaults.registerPerIp()).isEqualTo(5);
        assertThat(defaults.bootstrapPerIp()).isEqualTo(3);
        assertThat(defaults.passwordPerIp()).isEqualTo(10);
        assertThat(defaults.llmPerLearner()).isEqualTo(20);
    }
}
