package com.syllabai.identity;

import com.syllabai.identity.Role;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local-development convenience: creates demo accounts so the API can be exercised
 * immediately (docker-compose Postgres + `local` profile). Never active outside
 * the local profile; passwords are demo-only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "syllabai.seed.demo-users", name = "enabled", havingValue = "true")
public class DemoUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final AuthService authService;

    public DemoUserSeeder(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        provision("teacher@syllabai.dev", "teacher-demo-1234", "Demo Teacher", Set.of(Role.TEACHER));
        provision("student@syllabai.dev", "student-demo-1234", "Demo Student", Set.of(Role.STUDENT));
        provision("admin@syllabai.dev", "admin-demo-1234", "Demo Admin", Set.of(Role.ADMIN, Role.TEACHER));
    }

    private void provision(String email, String password, String displayName, Set<Role> roles) {
        try {
            authService.provisionUser(email, password, displayName, roles);
            log.info("demo user provisioned: {} (credentials: DEV-ONLY, see DemoUserSeeder "
                    + "source — never log secrets)", email);
        } catch (com.syllabai.shared.ConflictException alreadyExists) {
            log.debug("demo user already present: {}", email);
        }
    }
}
