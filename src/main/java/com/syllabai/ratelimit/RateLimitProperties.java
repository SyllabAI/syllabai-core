package com.syllabai.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit budgets (deep-audit 09-28 M1: the service shipped with no
 * throttling at all — login brute-force and LLM cost amplification were both
 * unbounded). Two tiers:
 *
 * <ul>
 *   <li><strong>auth</strong> — the pre-authentication identity endpoints,
 *       keyed by client IP: login (credential brute force), register
 *       (account spam), bootstrap-admin (one-time gate, still tight),
 *       password change (authenticated but credential-sensitive);</li>
 *   <li><strong>llm</strong> — the two learner surfaces whose handling
 *       invokes the LLM chain, keyed by learner: tutor ask + CLA ask. The
 *       default stays comfortably above the CI anchor sweep's per-learner
 *       ask rate (~11 asks/run, fresh learner per run) so the harness never
 *       trips it.</li>
 * </ul>
 *
 * <p>Counters are in-memory fixed windows (single-instance deployment on
 * Render). Horizontal scale-out would need a shared store — deliberately out
 * of scope here.</p>
 *
 * @param enabled        master switch (the IT profile turns this off)
 * @param window         fixed-window length shared by every budget
 * @param loginPerIp     POST /api/v1/auth/login per IP per window
 * @param registerPerIp  POST /api/v1/auth/register per IP per window
 * @param bootstrapPerIp POST /api/v1/auth/bootstrap-admin per IP per window
 * @param passwordPerIp  POST /api/v1/auth/password per IP per window
 * @param llmPerLearner  tutor/CLA asks per learner per window
 */
@ConfigurationProperties(prefix = "syllabai.ratelimit")
public record RateLimitProperties(
        Boolean enabled,
        Duration window,
        Integer loginPerIp,
        Integer registerPerIp,
        Integer bootstrapPerIp,
        Integer passwordPerIp,
        Integer llmPerLearner) {

    public RateLimitProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        window = window == null ? Duration.ofSeconds(60) : window;
        loginPerIp = loginPerIp == null ? 10 : loginPerIp;
        registerPerIp = registerPerIp == null ? 5 : registerPerIp;
        bootstrapPerIp = bootstrapPerIp == null ? 3 : bootstrapPerIp;
        passwordPerIp = passwordPerIp == null ? 10 : passwordPerIp;
        llmPerLearner = llmPerLearner == null ? 20 : llmPerLearner;
    }
}
