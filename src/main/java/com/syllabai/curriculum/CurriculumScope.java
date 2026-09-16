package com.syllabai.curriculum;

import java.util.Set;
import java.util.UUID;

/**
 * The resolved retrieval scope for one curriculum version (T-C07) — the single
 * value every chunk-serving and KG-intent path must be narrowed by, so a
 * learner of one curriculum can never be served evidence from another.
 *
 * <p>Two payloads, one per scoped surface:</p>
 * <ul>
 *   <li>{@code curriculumVersionId} — the mandatory query-time predicate for the
 *       chunk surface (the verified join path {@code document_chunks → documents
 *       → exam_papers (QP/MS document_id) → subjects → curriculum_versions};
 *       DB-verified 2026-09-17: 2,333/2,333 chunks resolve, zero double-join).</li>
 *   <li>{@code intentSurfaceNodeIds} — the PART_OF subtree id set under the
 *       curriculum's subject roots; the KG intent surface. DB-verified: the
 *       {@code 4CH1} subject-root subtree covers all 226 VALIDATED structure
 *       nodes with zero VALIDATED structure nodes outside, and structurally
 *       excludes the ING- and WCH11- scrape prefixes (AF-1 containment).</li>
 * </ul>
 *
 * <p>Fail-closed contract: an empty {@code intentSurfaceNodeIds} is a valid
 * scope that matches nothing (evidence empty → deterministic refusal); there is
 * deliberately no "unscoped" mode anywhere in the serving paths — a scope that
 * cannot be resolved is a refusal, never a wildcard.</p>
 */
public record CurriculumScope(UUID curriculumVersionId, String code,
                              Set<UUID> intentSurfaceNodeIds) {

    public CurriculumScope {
        java.util.Objects.requireNonNull(curriculumVersionId, "curriculumVersionId");
        java.util.Objects.requireNonNull(code, "code");
        java.util.Objects.requireNonNull(intentSurfaceNodeIds, "intentSurfaceNodeIds");
        intentSurfaceNodeIds = Set.copyOf(intentSurfaceNodeIds);
    }
}
