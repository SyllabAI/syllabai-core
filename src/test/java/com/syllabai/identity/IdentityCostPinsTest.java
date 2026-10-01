package com.syllabai.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the two promoted audit residuals (re-derivation pass 09-28:
 * accepted-with-rationale → implemented by operator decision "proceed with
 * BCrypt cost 12 + shorter JWT TTL").
 *
 * <p>Both are silent-regression risks: a refactor back to
 * {@code new BCryptPasswordEncoder()} or a re-extended TTL compiles, passes
 * every functional test, and quietly re-widens the attack surface. So the
 * pins check the WIRING, not a constant — the real bean's hash prefix
 * (BCrypt embeds its cost factor), the production yml value, and JwtService's
 * {@code @Value} fallback (used by profiles that don't set the property).</p>
 */
class IdentityCostPinsTest {

    /** BCrypt embeds the cost in the hash itself: {@code $2a$12$...}. */
    @Test
    void bcryptCostIsPinnedAtTwelve() {
        SecurityConfig config = new SecurityConfig(null, null, new SecurityProperties(null, null, null));
        PasswordEncoder encoder = config.passwordEncoder();
        String hash = encoder.encode("pin-check-password-123");
        assertTrue(hash.matches("^\\$2[aby]\\$12\\$.*"),
                "BCrypt cost regressed below 12 (audit residual): " + hash);
        assertTrue(encoder.matches("pin-check-password-123", hash));
    }

    /** application.yml is the production truth for the TTL (env-overridable). */
    @Test
    @SuppressWarnings("unchecked")
    void jwtTtlYamlDefaultsToTwoHours() {
        InputStream is = getClass().getClassLoader().getResourceAsStream("application.yml");
        assertNotNull(is, "application.yml must be on the test classpath");
        Map<String, Object> root = new Yaml().load(is);
        Map<String, Object> syllabai = (Map<String, Object>) root.get("syllabai");
        Map<String, Object> security = (Map<String, Object>) syllabai.get("security");
        Object ttl = security.get("jwt-ttl");
        assertNotNull(ttl, "syllabai.security.jwt-ttl must stay declared in application.yml");
        // raw value is the placeholder ${SYLLABAI_JWT_TTL:PT2H} — extract the fallback
        String resolved = String.valueOf(ttl).replaceFirst("^[^:]*:", "").replace("}", "").trim();
        assertEquals("PT2H", resolved, "jwt-ttl regressed past 2h (audit residual)");
    }

    /** JwtService's @Value fallback must agree with the yml (property-less profiles). */
    @Test
    void jwtServiceValueFallbackDefaultsToTwoHours() {
        for (Constructor<?> ctor : JwtService.class.getDeclaredConstructors()) {
            for (java.lang.annotation.Annotation[] param : ctor.getParameterAnnotations()) {
                for (java.lang.annotation.Annotation a : param) {
                    if (a instanceof Value v
                            && v.value().equals("${syllabai.security.jwt-ttl:PT2H}")) {
                        return; // pinned fallback found
                    }
                }
            }
        }
        fail("JwtService's @Value jwt-ttl fallback must be ${syllabai.security.jwt-ttl:PT2H}");
    }
}
