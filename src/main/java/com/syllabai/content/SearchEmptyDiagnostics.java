package com.syllabai.content;

/**
 * T-C31 serving-emptiness observability: the stage-funnel counts behind an
 * empty teacher content search, computed ONLY on the empty path (the happy
 * path runs exactly the queries it ran before — zero added cost).
 *
 * <p>Funnel (each stage strictly narrows the previous one):</p>
 * <ol>
 *   <li>{@code chunksInScope} — chunks matching the curriculum-scope EXISTS
 *       predicate (both branches, NO validation gates) and the kind filter.</li>
 *   <li>{@code embeddedInScope} — of those, the ones carrying any embedding.</li>
 *   <li>{@code inScopeAtRev} — of those, the ones at
 *       {@link ChunkVectorRepository#CURRENT_EMBED_REV} (the corpus-generation
 *       read filter).</li>
 *   <li>{@code servingEligible} — of those, the ones passing the T-C20
 *       VALIDATED-only gates — the EXACT {@code searchServingEligible} WHERE
 *       predicate, mirrored verbatim (drift-guarded by test).</li>
 * </ol>
 *
 * <p>{@link #cause()} classifies the funnel: the first zero stage names the
 * cause ({@link SearchEmptyCause}). The counts are contractually monotone
 * non-increasing; a violated invariant classifies as
 * {@link SearchEmptyCause#UNEXPECTED} — fail loud, never guess.</p>
 */
public record SearchEmptyDiagnostics(long chunksInScope, long embeddedInScope,
                                     long inScopeAtRev, long servingEligible) {

    /** Classifies this funnel into the {@link SearchEmptyCause} it names. */
    public SearchEmptyCause cause() {
        if (chunksInScope < 0 || chunksInScope < embeddedInScope
                || embeddedInScope < inScopeAtRev || inScopeAtRev < servingEligible) {
            return SearchEmptyCause.UNEXPECTED;
        }
        if (chunksInScope == 0) {
            return SearchEmptyCause.SCOPE_EMPTY;
        }
        if (embeddedInScope == 0) {
            return SearchEmptyCause.NOT_EMBEDDED;
        }
        if (inScopeAtRev == 0) {
            return SearchEmptyCause.EMBED_REV_EMPTY;
        }
        if (servingEligible == 0) {
            return SearchEmptyCause.VALIDATION_GATE_EMPTY;
        }
        return SearchEmptyCause.UNEXPECTED;
    }
}
