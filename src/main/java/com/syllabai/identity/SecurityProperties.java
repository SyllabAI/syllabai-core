package com.syllabai.identity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security-related configuration (prefix {@code syllabai.security}).
 *
 * @param jwtSecret          HS256 signing secret, min 32 bytes; env SYLLABAI_JWT_SECRET
 * @param jwtTtl             access-token lifetime (default 12h)
 * @param corsAllowedOrigins browser origins allowed to call the API (Vercel + dev)
 */
@ConfigurationProperties(prefix = "syllabai.security")
public record SecurityProperties(
        String jwtSecret,
        Duration jwtTtl,
        List<String> corsAllowedOrigins) {

    public SecurityProperties {
        jwtTtl = jwtTtl == null ? Duration.ofHours(12) : jwtTtl;
        corsAllowedOrigins = corsAllowedOrigins == null || corsAllowedOrigins.isEmpty()
                ? List.of("http://localhost:3000")
                : List.copyOf(corsAllowedOrigins);
    }
}
