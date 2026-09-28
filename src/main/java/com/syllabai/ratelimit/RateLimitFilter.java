package com.syllabai.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting at the security-chain boundary (deep-audit 09-28 M1). Two
 * budgets, enforced BEFORE any controller work:
 *
 * <ul>
 *   <li><strong>auth tier</strong> — pre-authentication identity endpoints,
 *       keyed by client IP, so credential brute force and account spam burn
 *       through a bounded budget per host;</li>
 *   <li><strong>LLM tier</strong> — {@code POST /api/v1/tutor/ask} and
 *       {@code POST /api/v1/learners/me/cla/ask}, keyed by learner (the JWT
 *       user id the {@code JwtAuthenticationFilter} placed on the request —
 *       this filter runs AFTER it inside the chain), with an IP fallback.
 *       Every admitted ask pays for tokens; this caps the cost-amplification
 *       vector.</li>
 * </ul>
 *
 * <p>Algorithm: in-memory fixed windows (window start + count per key) —
 * deterministic, allocation-light, trivially testable; the burst-doubling at
 * window boundaries is acceptable at these budgets. Keys are low-cardinality
 * (client IPs, learner ids) and stale windows are swept on a size guard, so
 * the map stays bounded.</p>
 *
 * <p>Client IP: Render terminates the connection on a proxy, so
 * {@code X-Forwarded-For} (left-most hop) is authoritative when present and
 * {@code getRemoteAddr()} is the fallback. A caller able to spoof XFF can
 * rotate its own auth bucket — that buys it nothing on the LLM tier (learner
 * keyed) and is equivalent to rotating hosts, which a single-instance
 * in-memory limiter never claimed to stop.</p>
 *
 * <p>Failure posture: an unexpected exception inside the limiter is logged
 * and the request CONTINUES (fail-open). This is an availability/cost
 * control, not a data gate — the repo's fail-closed rule protects correctness
 * boundaries (auth, scope, grounding, persistence); a limiter bug that
 * denied the whole service would be a worse failure than the one it guards
 * against. The limiter itself is pure in-memory arithmetic with no external
 * dependency, so the fail-open path is theoretical by construction.</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** hard cap before a sweep of expired windows runs (IPs + learner ids
     *  never approach this in legitimate traffic; it bounds a key flood) */
    static final int MAX_KEYS = 100_000;

    private final RateLimitProperties properties;
    private final Clock clock;

    /** key -> window state; start = beginning of the current fixed window */
    private final ConcurrentHashMap<String, Window> buckets = new ConcurrentHashMap<>();

    private record Window(Instant start, int count) {
        Window bumped() {
            return new Window(start, count + 1);
        }
    }

    /** one request's classification: a key prefix (tier+route), the subject
     *  that tier is keyed by, and the per-window limit (package-private so
     *  the fail-open test can override the classification) */
    record Budget(String tier, String subject, int limit) {
        String key() {
            return tier + ":" + subject;
        }
    }

    @Autowired
    public RateLimitFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** test-visible clock: window expiry is exercised without sleeping */
    RateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!Boolean.TRUE.equals(properties.enabled()) || HttpMethod.OPTIONS.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            Budget budget = budgetOf(request);
            if (budget == null) {
                chain.doFilter(request, response);
                return;
            }
            admit(request, response, chain, budget);
        } catch (RuntimeException e) {
            // fail-open: see the class javadoc — availability control, not a gate
            log.error("rate limiter failed open on an internal error: {}", e.toString());
            chain.doFilter(request, response);
        }
    }

    /** package-private: the fail-open test subclasses the filter with a
     *  classification that throws, pinning the continue-the-chain behavior */
    Budget budgetOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = HttpMethod.POST.matches(request.getMethod());
        if (post && "/api/v1/auth/login".equals(path)) {
            return new Budget("auth:login", clientIp(request), properties.loginPerIp());
        }
        if (post && "/api/v1/auth/register".equals(path)) {
            return new Budget("auth:register", clientIp(request), properties.registerPerIp());
        }
        if (post && "/api/v1/auth/bootstrap-admin".equals(path)) {
            return new Budget("auth:bootstrap", clientIp(request), properties.bootstrapPerIp());
        }
        if (post && "/api/v1/auth/password".equals(path)) {
            return new Budget("auth:password", clientIp(request), properties.passwordPerIp());
        }
        if (post && ("/api/v1/tutor/ask".equals(path)
                || "/api/v1/learners/me/cla/ask".equals(path)
                // Smart Mark surfaces drive the LLM chain too — smart-mark runs
                // the marking pipeline ONCE PER PART, feedback-explanation and
                // improvement-plan are one generation each (R8: the M1 cost
                // tier must cover every learner-reachable LLM spend, not just
                // the two ask routes)
                || (path.startsWith("/api/v1/learners/me/attempts/")
                    && (path.endsWith("/smart-mark")
                        || path.endsWith("/feedback-explanation")
                        || path.endsWith("/improvement-plan"))))) {
            return new Budget("llm:ask", learnerKey(request), properties.llmPerLearner());
        }
        return null;
    }

    /** LLM tier key: the JWT user id first (stable per learner regardless of
     *  host rotation), then the authenticated subject, then the client IP. */
    private static String learnerKey(HttpServletRequest request) {
        Object userId = request.getAttribute("com.syllabai.userId");
        if (userId != null) {
            return String.valueOf(userId);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return clientIp(request);
    }

    /**
     * Client-IP key for the auth tier — trusted-chain walk. Live probing
     * (2026-09-28) established Render's actual header behavior:
     * X-Forwarded-For arrives as [client-supplied hops..., real peer,
     * internal hop(s)] — the proxy APPENDS the observed peer and at least one
     * further PRIVATE internal hop whose address varies per request. So the
     * RIGHTMOST entry is infrastructure (the old leftmost key was
     * attacker-controlled — rotating a fake leftmost minted fresh budgets —
     * and a naive rightmost key degenerates to varying infrastructure
     * addresses). The correct key is the FIRST PUBLIC address walking from
     * the right: everything the client supplied sits left of the proxy's
     * append, so it can never be reached by this walk. All-private headers
     * (direct internal traffic) fall back to the socket peer.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].strip();
                if (hop.isEmpty() || isPrivateAddress(hop)) {
                    continue; // trusted infrastructure hop — keep walking left
                }
                return hop;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** Reserved-range check: these rightmost values are infrastructure, not clients. */
    private static boolean isPrivateAddress(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
                || ip.startsWith("169.254.") || ip.startsWith("fe80:") || ip.startsWith("fc")
                || ip.startsWith("fd") || ip.equals("::1") || ip.startsWith("172.16.")
                || ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.2") || ip.startsWith("172.30.") || ip.startsWith("172.31.");
    }

    private void admit(HttpServletRequest request, HttpServletResponse response,
                       FilterChain chain, Budget budget) throws ServletException, IOException {
        Instant now = clock.instant();
        long windowMillis = properties.window().toMillis();
        Instant windowStart = Instant.ofEpochMilli((now.toEpochMilli() / windowMillis) * windowMillis);
        // retry-after is the same whether this request is admitted or not
        int retryAfterSeconds = (int) Math.max(1,
                (windowStart.plusMillis(windowMillis).toEpochMilli() - now.toEpochMilli() + 999) / 1000);

        Window updated = buckets.compute(budget.key(), (k, existing) ->
                existing == null || !existing.start().equals(windowStart)
                        ? new Window(windowStart, 1)
                        : existing.bumped());
        if (buckets.size() > MAX_KEYS) {
            sweepExpired(now);
        }
        if (updated.count() > budget.limit()) {
            log.info("rate limit {} exceeded for {} (window {}/{})",
                    budget.tier(), budget.subject(), updated.count(), budget.limit());
            reject(response, retryAfterSeconds);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        // the ApiError field shape every other boundary returns
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Too many requests. Wait a moment and try again.\","
                + "\"retryAfterSeconds\":" + retryAfterSeconds
                + ",\"timestamp\":\"" + Instant.now() + "\"}");
    }

    /** evict windows that started at least two window-lengths ago (bounded-
     *  memory guard for a key flood; legitimate traffic never triggers this) */
    private void sweepExpired(Instant now) {
        Instant cutoff = now.minus(properties.window().multipliedBy(2));
        for (Map.Entry<String, Window> entry : buckets.entrySet()) {
            if (entry.getValue().start().isBefore(cutoff)) {
                buckets.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
