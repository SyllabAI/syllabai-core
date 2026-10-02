package com.syllabai.identity;

import java.util.List;
import com.syllabai.ratelimit.RateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security with RBAC (Master Spec §6.1, §20, T-003).
 *
 * <p>Public surface: register/login, actuator health, API docs. Everything else
 * requires authentication. Role enforcement is method-level (@PreAuthorize) plus
 * coarse route rules for teacher/admin surfaces. CORS allows the configured
 * frontend origins (Vercel + localhost dev, Master Spec §2.2).</p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final SecurityProperties properties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RateLimitFilter rateLimitFilter,
                          SecurityProperties properties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.properties = properties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // security headers (R4): behind Render's TLS-terminating proxy
                // Tomcat sees plain HTTP, so the default isSecure()-gated HSTS
                // writer never fires — the requestMatcher forces HSTS on every
                // response; referrer policy on top of the nosniff/frame/cache
                // defaults Spring Security already emits
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .requestMatcher(request -> true)
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                                .preload(true))
                        .referrerPolicy(referrer -> referrer
                                .policy(org.springframework.security.web.header.writers
                                        .ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                // one-time first-admin bootstrap (V19): gated by the
                                // bootstrap_admin_state row + zero-admins invariant
                                "/api/v1/auth/bootstrap-status",
                                "/api/v1/auth/bootstrap-admin").permitAll()
                        .requestMatchers("/error").permitAll()   // error dispatch must not re-authenticate
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers(
                                "/api/v1/api-docs/**",
                                "/api/v1/docs/**",
                                "/api/v1/docs",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/teacher/**").hasAnyRole("TEACHER", "ADMIN")
                        // research aggregates span ALL learners (S2/ADR-033) — teacher+
                        // here, re-gated at the controller method level as well
                        .requestMatchers("/api/v1/research/**").hasAnyRole("TEACHER", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        (request, response, authException) ->
                                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // rate limiting (deep-audit 09-28 M1) runs inside the security
                // chain AFTER the JWT filter: the LLM tier keys on the learner
                // identity the JWT filter resolved
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /** the limiter must run ONLY inside the security chain (the LLM tier
     *  needs the JWT context); this registration-off bean stops Spring Boot
     *  from ALSO auto-registering the @Component filter on the servlet
     *  chain, where it would double-count and run pre-authentication */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * BCrypt cost 12 (audit re-derivation residual, accepted-with-rationale
     * 09-28 → promoted by operator decision): OWASP's floor is 10, but the
     * login path is now rate-budget-bound (M1 + R5), so the extra ~2x per
     * encode/match is affordable and each cost step doubles offline-cracking
     * work. Only budget-gated surfaces pay it (login/register/bootstrap/
     * password change) — the JWT filter never touches BCrypt. Existing hashes
     * carry their own embedded cost factor, so old credentials keep verifying
     * without a migration; they re-hash at cost 12 only if a rotation flow
     * re-encodes them.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
