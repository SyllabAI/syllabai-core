package com.syllabai.retrieval;

import com.syllabai.content.Document;
import com.syllabai.curriculum.CurriculumScope;
import java.util.Set;
import java.util.UUID;

/**
 * The provider-neutral query contract of the retrieval fabric (T-C14; contract
 * sketch §2). One immutable query any candidate-generation arm can be asked —
 * lexical, vector, KG or File Search — without provider-specific shapes.
 *
 * <p><strong>Deviation from the sketch, recorded:</strong> the sketch carried a
 * bare {@code curriculumVersionId: UUID}. T-C07 landed after the sketch and
 * made the resolved {@link CurriculumScope} the canonical scope carrier on
 * every retrieval port (it also holds the KG intent surface the
 * authoritative-KG adapter needs). The fabric therefore carries the scope
 * object and exposes {@link #curriculumVersionId()} as a derived accessor —
 * one scope, no duplicated curriculum id, and the T-C07 fail-closed invariant
 * (a scope that cannot be resolved is a refusal, never a wildcard) is enforced
 * here by construction: {@code scope} is non-null in every instance.</p>
 *
 * @param normalizedQuery      the learner question, normalized by the caller
 *                             (whitespace-collapsed). Blank is allowed here;
 *                             lexical providers fail-closed to an empty result.
 * @param scope                the resolved curriculum scope (non-null —
 *                             retrieval never runs unscoped, T-C07)
 * @param specificationPointIds optional narrowing, e.g. {@code "4CH1-1.26"}
 *                             (empty = no narrowing)
 * @param conceptIds           optional concept narrowing (empty = none)
 * @param misconceptionIds     optional misconception narrowing (empty = none)
 * @param resourceKinds        optional document-kind narrowing (empty = all kinds)
 * @param learnerSignals       learner-state ranking signal (research doc §5,
 *                             Phase G — never a filter, ranking only; empty() today)
 * @param evidence             evidence sufficiency hints (empty defaults today)
 * @param limit                candidate bound (pre-fusion), ≥ 0
 */
public record StructuredRetrievalQuery(
        String normalizedQuery,
        CurriculumScope scope,
        Set<String> specificationPointIds,
        Set<UUID> conceptIds,
        Set<UUID> misconceptionIds,
        Set<Document.Kind> resourceKinds,
        LearnerSignals learnerSignals,
        EvidenceRequirements evidence,
        int limit) {

    public StructuredRetrievalQuery {
        java.util.Objects.requireNonNull(normalizedQuery, "normalizedQuery");
        java.util.Objects.requireNonNull(scope,
                "curriculum scope is mandatory — retrieval never runs unscoped (T-C07)");
        specificationPointIds = specificationPointIds == null ? Set.of() : Set.copyOf(specificationPointIds);
        conceptIds = conceptIds == null ? Set.of() : Set.copyOf(conceptIds);
        misconceptionIds = misconceptionIds == null ? Set.of() : Set.copyOf(misconceptionIds);
        resourceKinds = resourceKinds == null ? Set.of() : Set.copyOf(resourceKinds);
        learnerSignals = learnerSignals == null ? LearnerSignals.empty() : learnerSignals;
        evidence = evidence == null ? EvidenceRequirements.defaults() : evidence;
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0, got " + limit);
        }
    }

    /** The mandatory T-C07 curriculum predicate id (derived from the scope). */
    public UUID curriculumVersionId() {
        return scope.curriculumVersionId();
    }

    /** Minimal query for lexical/provider probes: text + scope + limit. */
    public static StructuredRetrievalQuery of(String normalizedQuery, CurriculumScope scope, int limit) {
        return new StructuredRetrievalQuery(normalizedQuery, scope, null, null, null, null, null, null, limit);
    }
}
