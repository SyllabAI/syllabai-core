package com.syllabai.bench;

import com.syllabai.retrieval.BoundaryPolicy;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * T-C77 (rank-quality lane tranche 7): the rank-bound seam in RANK-CAP form —
 * the TWO-ARM HORIZON PARTITION of the fused pool behind
 * {@code BENCH_NOTES_RANK_CAP}, pre-registered in
 * {@code RANK-BOUNDED-KIND-ADMISSION-PREREGISTRATION.md} (records 5c0b6b5)
 * BEFORE any implementation or run.
 *
 * <p><strong>Semantics (pinned by the pre-registration, §1):</strong> the
 * served order of EACH fabric (served {@code allowAll} + compliant
 * central-VALIDATED) is the fused pool RE-PARTITIONED as — <em>prefix</em>:
 * every pool ref that carries a two-arm contribution (arm A pgvector or arm B
 * bm25 ranked it), ordered by its two-arm RRF score (k=60 over the two arms
 * only — the kind-arm's additive term {@code 1/(60+r_kind+1)} STRIPPED), ties
 * chunk_ref ASC; <em>tail</em>: every kind-arm-only ref (no two-arm
 * contribution), ordered by kind-arm rank, ties chunk_ref ASC. The kind-arm
 * continues to ADMIT exactly as recorded (its list, its SET contribution, the
 * pool — all unchanged); its ONLY ranking effect is that its exclusive mass
 * serves below the entire two-arm surface. The bound is a pure post-fusion
 * served-order partition: it cannot admit a candidate the boundary excluded,
 * cannot invent one, and cannot drop one (set-preservation checked
 * fail-closed on every call — the RerankedRetrieval wrapper discipline).</p>
 *
 * <p><strong>Why the strip is exact:</strong> the fused score of a ref is the
 * sum of its per-arm RRF contributions ({@code weight/(k+rank0+1)}, rank0 the
 * position in the arm's list); the grounding proved byte-exact on the recorded
 * packs (3596/3596 refs) that {@code h_score − f_score ∈ {0, 1/(60+r_kind)}}
 * — the kind-arm's ONLY effect on existing refs is the additive term. This
 * seam mirrors the fabric's own arithmetic per view: the fusion consumed each
 * arm's list filtered through the SAME {@link BoundaryPolicy} (pre-fusion), so
 * the wrapper filters the kind-arm's list through the view's boundary and
 * strips {@code 1/(k+rank0+1)} at the filtered rank — term-for-term the
 * contribution the shipped fuser added (Run005CRankCapTest cross-checks the
 * strip against a real {@code ReciprocalRankFusion} fusion, so any drift in
 * k or in the fabric's rank semantics fails the build instead of the run).</p>
 *
 * <p><strong>The protected horizon H:</strong> the gate value (run posture
 * H = 20) is asserted, not enforced by exclusion — the partition guarantees
 * 0 kind-arm-only refs at fused ranks 1..H whenever the two-arm prefix ≥ H;
 * a shorter prefix (under-fill) is RECORDED honestly per query (tracked by
 * stripped query text, idempotent across the determinism double-pass — the
 * NotesArmRetrieval precedent), never silently patched.</p>
 *
 * <p><strong>Absent-path identity:</strong> {@code BENCH_NOTES_RANK_CAP}
 * absent means this class is never CONSTRUCTED — the run-005-h path is
 * byte-identical by construction (the S8D/T-C65/T-C69/T-C72 absent-path
 * precedent). No production file reads the gate: serving's own candidate
 * selection and fusion are untouched. Determinism: the transform is a pure
 * function of the three arm lists (no clocks, no gold knowledge, no
 * per-query parameters) — the bench double-pass byte-identity contract
 * extends to it by construction.</p>
 */
public final class NotesRankCapRetrieval {

    /**
     * The RRF smoothing constant the recorded posture fuses with — the same
     * literal Run005C constructs {@code new ReciprocalRankFusion(60)} with.
     * The strip arithmetic must divide by exactly this k; the
     * Run005CRankCapTest contribution-stripping cross-check pins the two
     * together (a one-sided change fails the build, not the run).
     */
    static final int RRF_K = 60;

    private final RerankedRetrieval.FusedRetriever delegate;
    private final NotesArmRetrieval notesArm;
    private final int horizon;
    /** The view's OWN boundary — mirrors the policy the inner fabric applied pre-fusion. */
    private final BoundaryPolicy boundary;
    /** The two recorded arms whose contributions form the prefix surface (arm A + arm B ids). */
    private final Set<String> twoArmIds;
    /** Query texts (stripped) whose two-arm surface came up shorter than H. */
    private final Set<String> underFilled = new LinkedHashSet<>();

    NotesRankCapRetrieval(RerankedRetrieval.FusedRetriever delegate,
                          NotesArmRetrieval notesArm, int horizon,
                          BoundaryPolicy boundary, Set<String> twoArmIds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate retriever");
        this.notesArm = Objects.requireNonNull(notesArm, "notes arm (the bounded arm)");
        this.boundary = Objects.requireNonNull(boundary, "view boundary");
        this.twoArmIds = Set.copyOf(Objects.requireNonNull(twoArmIds, "two-arm ids"));
        if (horizon <= 0) {
            throw new IllegalArgumentException("rank-cap horizon must be > 0, got " + horizon
                    + " (0 is the absent path — never construct the transform)");
        }
        if (twoArmIds.isEmpty()) {
            throw new IllegalArgumentException("two-arm ids must name the recorded arms"
                    + " (the prefix surface would be empty — fail-closed)");
        }
        if (this.twoArmIds.contains(notesArm.id())) {
            throw new IllegalArgumentException("the bounded arm's id '" + notesArm.id()
                    + "' must not appear among the two-arm ids (dichotomy integrity — fail-closed)");
        }
        this.horizon = horizon;
    }

    /**
     * The T-C77 composition seam: {@code horizon <= 0} (the gate absent) keeps
     * the delegate untouched — the caller never reassigns, the run-005-h path
     * stays byte-identical by construction (the RerankedRetrieval.maybeWrap
     * precedent). {@code horizon > 0} requires the bounded arm (the five-gate
     * guard's REQUIRES clause, enforced here again at the construction point).
     */
    static NotesRankCapRetrieval maybeWrap(RerankedRetrieval.FusedRetriever delegate,
                                           NotesArmRetrieval notesArm, int horizon,
                                           BoundaryPolicy boundary, Set<String> twoArmIds) {
        if (horizon <= 0) {
            return null;
        }
        Objects.requireNonNull(notesArm, "notes arm (BENCH_NOTES_RANK_CAP requires "
                + "BENCH_NOTES_KIND_ARM — a bound with no bounded arm, fail-closed)");
        return new NotesRankCapRetrieval(delegate, notesArm, horizon, boundary, twoArmIds);
    }

    /**
     * Spec parser: absent/blank = 0 (the run-005-h posture, byte-identical —
     * the transform is never constructed); a positive integer = the protected
     * horizon H; anything else fails closed — never a silently-different
     * posture.
     */
    static int rankCapForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        String trimmed = spec.trim();
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unknown BENCH_NOTES_RANK_CAP spec: '" + spec
                    + "' (expected a positive integer horizon H, e.g. 20) — fail-closed");
        }
        if (value < 0 || value > 10_000) {
            throw new IllegalStateException("BENCH_NOTES_RANK_CAP out of range: " + value
                    + " (expected 0..10000) — fail-closed");
        }
        return value;
    }

    /** Queries (stripped embed keys) whose two-arm surface came up shorter than H. */
    int underFilledQueries() {
        return underFilled.size();
    }

    /** The protected horizon this transform asserts (the {@code BENCH_NOTES_RANK_CAP} value). */
    int horizon() {
        return horizon;
    }

    /**
     * The post-fusion partition: set-preserving, order-pinned, fail-closed on
     * every contract breach. The delegate's fused list is consumed as-is (the
     * boundary already applied pre-fusion inside the inner fabric); the
     * kind-arm's list is read from the arm itself (memoized there — the read
     * is O(1) with zero extra SQL, the pre-registered (e) latency claim's
     * O(n)-pass-only shape).
     */
    List<RetrievalFabric.FusedCandidate> retrieve(StructuredRetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        List<RetrievalFabric.FusedCandidate> fused = delegate.retrieve(query);
        if (fused.isEmpty()) {
            return fused;
        }

        // The kind-arm's list for this query, then the view's own pre-fusion
        // filter — the filtered positions ARE the ranks the shipped fuser
        // consumed for this view (RetrievalFabric filters every arm's list
        // through the ONE central boundary before fusion).
        List<RetrievalCandidate> kindList = notesArm.retrieve(query);
        Map<String, Integer> kindRank = new LinkedHashMap<>();
        int rank = 0;
        for (RetrievalCandidate candidate : kindList) {
            if (boundary.servingEligible(candidate)) {
                kindRank.putIfAbsent(candidate.evidenceLocator(), rank++);
            }
        }

        // Dichotomy pass: every fused candidate is either two-arm (prefix) or
        // kind-arm-only (tail); anything else is a composition drift and
        // fails closed.
        List<RetrievalFabric.FusedCandidate> prefix = new ArrayList<>();
        List<RetrievalFabric.FusedCandidate> tail = new ArrayList<>();
        Map<String, Double> stripped = new LinkedHashMap<>();
        Map<String, Integer> tailRank = new LinkedHashMap<>();
        for (RetrievalFabric.FusedCandidate fc : fused) {
            String locator = fc.candidate().evidenceLocator();
            boolean twoArm = false;
            for (String providerId : fc.providers()) {
                if (twoArmIds.contains(providerId)) {
                    twoArm = true;
                } else if (!providerId.equals(notesArm.id())) {
                    throw new IllegalStateException("fused candidate contributed by unknown arm '"
                            + providerId + "' — the partition's two-arm/kind-arm dichotomy "
                            + "requires the recorded arm composition (fail-closed)");
                }
            }
            Integer kindRank0 = kindRank.get(locator);
            if (twoArm) {
                double kindTerm = kindRank0 == null ? 0.0 : 1.0 / (RRF_K + kindRank0 + 1);
                stripped.put(locator, fc.fusedScore() - kindTerm);
                prefix.add(fc);
            } else {
                if (kindRank0 == null) {
                    throw new IllegalStateException("kind-arm-only fused candidate without a "
                            + "kind-arm rank: " + locator + " (partition dichotomy breach, "
                            + "fail-closed)");
                }
                tailRank.put(locator, kindRank0);
                tail.add(fc);
            }
        }

        // The pinned order: prefix by STRIPPED two-arm score DESC, ties
        // chunk_ref ASC; tail by kind-arm rank ASC, ties chunk_ref ASC.
        prefix.sort(Comparator
                .comparingDouble((RetrievalFabric.FusedCandidate c) ->
                        stripped.get(c.candidate().evidenceLocator()))
                .reversed()
                .thenComparing(c -> refOf(c.candidate())));
        tail.sort(Comparator
                .comparingInt((RetrievalFabric.FusedCandidate c) ->
                        tailRank.get(c.candidate().evidenceLocator()))
                .thenComparing(c -> refOf(c.candidate())));

        // The H assertion, honestly recorded: kind-arm-only refs serve inside
        // ranks 1..H exactly when the two-arm prefix is shorter than H. The
        // guarantee is conditional; the recording is not.
        if (prefix.size() < horizon) {
            String strippedText = query.normalizedQuery() == null
                    ? "" : query.normalizedQuery().strip();
            underFilled.add(strippedText);
        }

        // Rebuild: same candidates, same SET, partitioned order; each ref
        // records the score that ordered it — prefix the stripped two-arm
        // score, tail its kind-arm contribution (its full fused score, the
        // grounding's 797/797 shape).
        List<RetrievalFabric.FusedCandidate> out = new ArrayList<>(fused.size());
        for (RetrievalFabric.FusedCandidate fc : prefix) {
            out.add(new RetrievalFabric.FusedCandidate(fc.candidate(),
                    stripped.get(fc.candidate().evidenceLocator()), fc.providers()));
        }
        for (RetrievalFabric.FusedCandidate fc : tail) {
            out.add(fc);
        }
        if (out.size() != fused.size()) {
            throw new IllegalStateException("partition changed the candidate count: "
                    + fused.size() + " -> " + out.size()
                    + " (set-preservation breach, fail-closed)");
        }
        return List.copyOf(out);
    }

    /**
     * The portable chunk ref (spec §3.3: document checksum + chunk ordinal) —
     * the same identity Run005C.records and the grounding's chunk_ref ASC
     * tiebreaks use.
     */
    private static String refOf(RetrievalCandidate candidate) {
        return candidate.documentId() + ":"
                + candidate.metadata().getOrDefault("chunk_index", "-1");
    }
}
