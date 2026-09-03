package com.syllabai.infrastructure.llm;

import java.util.Optional;

/**
 * Port for resolving per-experiment provider/model pins (Master Spec §26.1, §19).
 *
 * <p>Implementations decide where pins live — the Spring configuration map
 * ({@code syllabai.llm.experiment-pins}) and/or the {@code experiments} research
 * registry. When a request carries an experiment id that no resolver can pin, the
 * {@link FailoverLlmChain} <strong>fails loudly</strong> rather than serving it from
 * an arbitrary provider: silent model drift mid-experiment is a provenance defect
 * for a research instrument.</p>
 */
public interface ExperimentPinResolver {

    /**
     * Resolve the pin for an experiment id, or empty when this resolver has no pin
     * for it (an empty result is not an error — other resolvers may know it).
     */
    Optional<ExperimentPin> resolve(String experimentId);
}
