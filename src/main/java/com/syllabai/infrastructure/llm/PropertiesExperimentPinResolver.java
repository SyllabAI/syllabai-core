package com.syllabai.infrastructure.llm;

import java.util.Map;
import java.util.Optional;

/**
 * Pin resolution from the {@code syllabai.llm.experiment-pins} configuration map
 * (Master Spec §26.1). Values are either {@code "provider"} or
 * {@code "provider:model"} — the deployment-level, version-controlled pin source
 * that takes precedence over the database registry.
 */
public final class PropertiesExperimentPinResolver implements ExperimentPinResolver {

    private final Map<String, String> pins;

    public PropertiesExperimentPinResolver(Map<String, String> pins) {
        this.pins = pins == null ? Map.of() : Map.copyOf(pins);
    }

    @Override
    public Optional<ExperimentPin> resolve(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            return Optional.empty();
        }
        String spec = pins.get(experimentId);
        if (spec == null || spec.isBlank()) {
            return Optional.empty();
        }
        String trimmed = spec.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            // no model part (or degenerate ":") — provider-only pin
            return Optional.of(new ExperimentPin(experimentId, trimmed, null));
        }
        return Optional.of(new ExperimentPin(
                experimentId,
                trimmed.substring(0, separator).trim(),
                trimmed.substring(separator + 1).trim()));
    }
}
