package com.syllabai.retrieval;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Learner-state ranking signals for the retrieval fabric (T-C14; contract
 * sketch §2; {@code RAG_RETRIEVAL_RESEARCH.md} §5). Read-only projections of
 * the learner model (BKT skill states, BDT misconception states, struggle
 * inference) — <strong>ranking signal only, never a filter and never
 * educational truth</strong>: a misconception label cannot create or mutate
 * curriculum relations.
 *
 * <p>Phase G of the research doc: no current provider consumes these; the
 * record exists so the contract does not have to break when they do. The
 * learner-module owners own the semantics of the referenced ids.</p>
 *
 * @param conceptMastery           mastery per concept id (0..1, BKT skill states)
 * @param activeMisconceptions     misconception node ids currently active for the learner
 * @param prerequisiteGaps         concept/prerequisite node ids inferred as unresolved
 * @param allowRemediationEvidence tutor policy consent to include remediation evidence
 */
public record LearnerSignals(Map<UUID, Double> conceptMastery,
                             Set<UUID> activeMisconceptions,
                             Set<UUID> prerequisiteGaps,
                             boolean allowRemediationEvidence) {

    public LearnerSignals {
        conceptMastery = conceptMastery == null ? Map.of() : Map.copyOf(conceptMastery);
        activeMisconceptions = activeMisconceptions == null ? Set.of() : Set.copyOf(activeMisconceptions);
        prerequisiteGaps = prerequisiteGaps == null ? Set.of() : Set.copyOf(prerequisiteGaps);
    }

    /** The no-signal default: all providers run learner-state-blind. */
    public static LearnerSignals empty() {
        return new LearnerSignals(Map.of(), Set.of(), Set.of(), false);
    }
}
