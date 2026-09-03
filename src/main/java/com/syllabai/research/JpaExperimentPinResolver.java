package com.syllabai.research;

import com.syllabai.infrastructure.llm.ExperimentPin;
import com.syllabai.infrastructure.llm.ExperimentPinResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves experiment pins from the {@code experiments} registry (Master Spec §19).
 * Only {@code RUNNING} experiments with a pinned provider resolve; anything else
 * (draft, paused, completed, or unpinned) counts as unresolved so the chain fails
 * loudly instead of drifting (§26.1). Implements the infrastructure port — the LLM
 * chain depends only on {@link ExperimentPinResolver}, never on the research module.
 */
@Component
public class JpaExperimentPinResolver implements ExperimentPinResolver {

    private final ExperimentRepository experiments;

    public JpaExperimentPinResolver(ExperimentRepository experiments) {
        this.experiments = experiments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExperimentPin> resolve(String experimentId) {
        if (experimentId == null || experimentId.isBlank()) {
            return Optional.empty();
        }
        return experiments.findByExperimentKey(experimentId)
                .filter(experiment -> experiment.status() == Experiment.Status.RUNNING)
                .filter(experiment -> experiment.pinnedProvider() != null
                        && !experiment.pinnedProvider().isBlank())
                .map(experiment -> new ExperimentPin(
                        experimentId, experiment.pinnedProvider(), experiment.pinnedModel()));
    }
}
