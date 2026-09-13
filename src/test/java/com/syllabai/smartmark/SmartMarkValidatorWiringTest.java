package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/**
 * Regression guard for the §23 factory wiring: the pipeline injects its validator
 * chain as an ordered list of beans, so a {@link MarkingValidator} implementation
 * without {@code @Component} silently drops out of the production chain — the
 * pipeline would then auto-accept unchecked LLM candidates while every unit test
 * still passes (tests hand-wire their validators).
 *
 * <p>These tests fail the moment a shipped validator loses its registration
 * annotation; full context wiring is exercised by the {@code @SpringBootTest}
 * integration flows.</p>
 */
class SmartMarkValidatorWiringTest {

    /** every deterministic validator the pipeline must run, by name. */
    private static final List<Class<? extends MarkingValidator>> REQUIRED = List.of(
            BoundsMarkingValidator.class,
            MarkSumMarkingValidator.class,
            CoverageMarkingValidator.class);

    @Test
    @DisplayName("every shipped validator is registered as a bean (@Component)")
    void validatorsAreRegisteredBeans() {
        for (Class<? extends MarkingValidator> type : REQUIRED) {
            assertThat(type.isAnnotationPresent(Component.class))
                    .as("%s must be @Component — an unregistered validator "
                            + "silently disables its check in production", type.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("validators are stateless: zero-arg constructible")
    void validatorsAreStateless() {
        for (Class<? extends MarkingValidator> type : REQUIRED) {
            assertThat(type.getDeclaredConstructors())
                    .as("%s must keep a zero-arg constructor for bean wiring",
                            type.getSimpleName())
                    .anyMatch(c -> c.getParameterCount() == 0);
        }
    }
}
