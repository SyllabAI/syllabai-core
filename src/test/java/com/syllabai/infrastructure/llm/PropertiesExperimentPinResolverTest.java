package com.syllabai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Configuration-map pin parsing ("provider" or "provider:model"). */
class PropertiesExperimentPinResolverTest {

    private final PropertiesExperimentPinResolver resolver =
            new PropertiesExperimentPinResolver(Map.of(
                    "cycle1-baseline", "groq",
                    "cycle1-rag-pilot", "groq:llama-3.3-70b-versatile"));

    @Test
    @DisplayName("provider-only pin resolves with a null model")
    void providerOnlyPin() {
        var pin = resolver.resolve("cycle1-baseline").orElseThrow();
        assertThat(pin.provider()).isEqualTo("groq");
        assertThat(pin.model()).isNull();
    }

    @Test
    @DisplayName("provider:model pin resolves both parts")
    void providerModelPin() {
        var pin = resolver.resolve("cycle1-rag-pilot").orElseThrow();
        assertThat(pin.provider()).isEqualTo("groq");
        assertThat(pin.model()).isEqualTo("llama-3.3-70b-versatile");
    }

    @Test
    @DisplayName("unknown experiment id resolves empty")
    void unknownExperimentIsEmpty() {
        assertThat(resolver.resolve("nope")).isEmpty();
    }

    @Test
    @DisplayName("null/blank experiment id resolves empty")
    void blankIdIsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
    }
}
