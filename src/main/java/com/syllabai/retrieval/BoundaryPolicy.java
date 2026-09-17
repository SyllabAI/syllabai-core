package com.syllabai.retrieval;

/**
 * The central serving-boundary policy of the retrieval fabric (T-C05; contract
 * sketch invariant 1: "validation-boundary exclusion is applied once centrally,
 * never per-provider"). The fabric is the single enforcement point: every
 * candidate from every provider passes through exactly one policy decision
 * before fusion, so a provider cannot widen serving and the policy cannot be
 * bypassed by composition order.
 *
 * <p>Why candidates and not a provider-side guard: providers see only their own
 * arm, so per-provider exclusion is enforcement by good intentions — the
 * Bm25Retriever arm-level guard (T-C14) is explicitly documented as interim
 * until this central point lands, and it "supersedes (never weakens)" it: the
 * central policy applies to EVERY leg uniformly, including future providers
 * that carry no arm-level guard at all. The policy must not depend on
 * {@link RetrievalCandidate#validationStatus()} (null when the source does not
 * carry it — never invented); a production policy resolves the serving state
 * from its own authority (paper state, resource kind, ...) by candidate
 * identity.</p>
 *
 * <p>Timing: the fabric applies the policy PRE-fusion, so each leg ranks
 * within its servable candidates — the post-fusion alternative would burn
 * top-k slots on unservable hits and starve the served result (the
 * "compliant-starved" shape run-004-a recorded as a post-hoc artifact).
 * Exclusion is a serving decision, never a re-ranking input.</p>
 */
@FunctionalInterface
public interface BoundaryPolicy {

    /**
     * @param candidate the candidate under central review (non-null)
     * @return {@code true} when the candidate is serving-eligible and may enter
     *         fusion; {@code false} excludes it from this orchestration entirely
     */
    boolean servingEligible(RetrievalCandidate candidate);

    /**
     * The pass-through policy: every candidate is eligible. This mirrors the
     * components' behaviors as they stand (e.g. the production vector surface
     * predates T-C05 — the T-C20 registered gap) and exists for measurement
     * honesty: a run scored under {@code allowAll} that surfaces non-VALIDATED
     * hits records them as VALIDATION_BOUNDARY_VIOLATION findings, exactly
     * like the pre-fabric arms. It is NOT a serving recommendation.
     */
    static BoundaryPolicy allowAll() {
        return candidate -> true;
    }
}
