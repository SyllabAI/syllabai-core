package com.syllabai.infrastructure.llm;

/**
 * A resolved experiment pin (Master Spec §26.1, §19 reproducibility): the provider —
 * and optionally the exact model — that must serve every request carrying this
 * experiment id. Pinned experiments never fail over to other providers.
 *
 * @param experimentId the experiment identifier (matches {@code experiments.experiment_key})
 * @param provider     registered provider name, e.g. "groq"
 * @param model        optional model override, e.g. "llama-3.3-70b-versatile"; null =
 *                     the provider's configured default model
 */
public record ExperimentPin(String experimentId, String provider, String model) {
}
