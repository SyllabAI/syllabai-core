package com.syllabai.infrastructure.llm;

import java.util.List;
import java.util.Optional;

/**
 * First-match-wins composition of {@link ExperimentPinResolver}s (Master Spec §23
 * composition). Order matters: deployment configuration overrides the database
 * registry, which overrides anything else — the chain wires this order explicitly.
 */
public final class CompositeExperimentPinResolver implements ExperimentPinResolver {

    private final List<ExperimentPinResolver> delegates;

    public CompositeExperimentPinResolver(List<ExperimentPinResolver> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Optional<ExperimentPin> resolve(String experimentId) {
        for (ExperimentPinResolver delegate : delegates) {
            Optional<ExperimentPin> pin = delegate.resolve(experimentId);
            if (pin.isPresent()) {
                return pin;
            }
        }
        return Optional.empty();
    }
}
