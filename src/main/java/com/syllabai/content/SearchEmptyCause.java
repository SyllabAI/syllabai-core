package com.syllabai.content;

/**
 * T-C31 serving-emptiness observability: WHY a teacher content search came back
 * empty. Before this classification, all three empty-causes behind the 0-hit
 * serving anomaly (TODO T-C23, evidence {@code evidence/serving-0hit-anomaly-2026-09-25/REPORT.md})
 * were indistinguishable — every cause returned the same {@code 200 + []}, and
 * neither the teacher nor ops could tell a scope refusal from a corpus-generation
 * mismatch from a validation-gated corpus without a DB session.
 *
 * <p>This enum labels the cause; it never changes WHAT is empty. The T-C20
 * boundary ({@code unvalidated_content_must_not_serve}) is untouched: an empty
 * result stays empty, on every cause. The cause is surfaced two ways on the
 * empty path only — a structured log line and the additive
 * {@code X-Search-Empty-Cause} response header (the JSON body stays a bare
 * array, byte-compatible with every existing consumer).</p>
 */
public enum SearchEmptyCause {

    /** {@code resolveActive} returned empty — the caller's curriculum is
     *  unresolved (the designed T-C07 refusal). No diagnostic SQL runs. */
    SCOPE_UNRESOLVED,

    /** A present {@code courseRef} named no ACTIVE surface-owning curriculum
     *  (the ADR-030 follow-through: fail-closed per-course search, the V53
     *  {@code resolveForCourse} semantics applied to this endpoint). Same
     *  refusal shape as {@link #SCOPE_UNRESOLVED} — no retrieval runs, no
     *  diagnostic SQL runs, and there is NO fallback to the global scope
     *  (a wrong-corpus answer is worse than an empty one). */
    COURSE_REF_UNRESOLVED,

    /** Scope resolved, but zero chunks match the scope/kind predicate at all —
     *  the corpus has nothing this caller's curriculum can see. */
    SCOPE_EMPTY,

    /** Chunks exist in scope but none carries an embedding — the embedding
     *  pipeline has not reached this slice (T-C07/T-C13 lineage). */
    NOT_EMBEDDED,

    /** Embedded in-scope chunks exist, but none at {@code CURRENT_EMBED_REV} —
     *  the corpus-generation mismatch that caused the 09-25 0-hit anomaly
     *  (rev supersession read filter, V33 plan §6). */
    EMBED_REV_EMPTY,

    /** Embedded in-scope chunks at the current rev exist, but every one is
     *  validation-gated (SUGGESTED/FLAGGED/REJECTED owning paper or document) —
     *  the T-C20 VALIDATED-only gate doing its job. The fix is content
     *  validation, never a gate change. */
    VALIDATION_GATE_EMPTY,

    /** Eligible rows exist yet the search returned empty — must never happen;
     *  fail-loud telemetry for a genuine anomaly. Also returned when the
     *  funnel counts violate their monotone-non-increasing invariant. */
    UNEXPECTED
}
