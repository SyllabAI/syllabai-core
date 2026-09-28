package com.syllabai.http;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Servlet registration for the JSON body limit (deep-audit 09-28 M4).
 *
 * <p>The filter is deliberately NOT a {@code @Component}: the explicit
 * registration alone controls both the ordering (very front of the chain,
 * before security — an oversized body is the one memory cost an
 * unauthenticated caller can trigger) and the URL scope ({@code /api/*} —
 * actuator and docs take no request bodies). Registering it as a component as
 * well would double-register it, double-count nothing but wrap twice — the
 * same trap the rate limiter's registration-off bean guards against.</p>
 */
@Configuration
public class HttpBoundaryConfig {

    @Bean
    FilterRegistrationBean<JsonBodyLimitFilter> jsonBodyLimitFilter(
            JsonBodyLimitProperties properties) {
        FilterRegistrationBean<JsonBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new JsonBodyLimitFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);   // after container encoding, before security
        registration.addUrlPatterns("/api/*");
        return registration;
    }
}
