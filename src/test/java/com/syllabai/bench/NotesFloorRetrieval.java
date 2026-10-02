package com.syllabai.bench;

import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * T-C69 (rank-quality lane tranche 5): the per-kind quota seam in FLOOR form —
 * the per-arm EXTERNAL_NOTES floor behind {@code BENCH_NOTES_FLOOR},
 * pre-registered in {@code POOL-COMPOSITION-PREREGISTRATION.md} (records
 * ee3e019) BEFORE any run.
 *
 * <p><strong>Semantics (pinned by the pre-registration):</strong> per arm,
 * AFTER the limit-N selection that reproduces the recorded run-005-f posture,
 * the arm's EXTERNAL_NOTES candidates in ARM-RANK order that are not already
 * in that arm's list are APPENDED, up to the floor (list length
 * {@code limit + up to floor}). The boundary and the fusion are untouched:
 * the floor operates on the provider's own ranking BEFORE the fabric's
 * central boundary and fusion, so the compliant view keeps excluding
 * non-VALIDATED papers exactly as recorded (notes chunks ride SUGGESTED
 * papers) and the shipped RRF reads the extended lists as ordinary arm
 * output — pure-additive admission, the pre-registered (d) monotonicity
 * theorem extends structurally. FLOOR form over CAP form is a declared
 * pre-registration design choice: the cap form displaces recorded candidates
 * and dies both ways.</p>
 *
 * <p><strong>Probing (exactness):</strong> the candidates beyond the recorded
 * cut are in no recorded artifact, so the floor discovers them by re-asking
 * the SAME delegate query at deeper LIMITs — a deterministic doubling window
 * ({@code limit+floor, limit+2*floor, ...}, hard-capped) until the floor is
 * filled or the arm is exhausted (fewer candidates than asked). Identical
 * probe limits run identically on the frozen corpus; the determinism
 * double-pass covers the extended path end-to-end.</p>
 *
 * <p><strong>Absent-path identity:</strong> a floor &le; 0 returns the
 * DELEGATE ITSELF (never a wrapper) — the recorded path is byte-identical by
 * construction (the S8D absent-path precedent, same shape as
 * {@link RerankedRetrieval#maybeWrap}).</p>
 */
public final class NotesFloorRetrieval {

    /** The only kind the floor admits (the pre-registration's single lever). */
    public static final String FLOOR_KIND = "EXTERNAL_NOTES";

    /** Probe-window hard cap (fail-safe against pathological loops; the frozen
     * corpus is ~4.5k chunks, so exhaustion always fires first in practice). */
    static final int MAX_EXTENSION = 5000;

    private NotesFloorRetrieval() {
    }

    /**
     * Spec parser: absent/blank = 0 (the recorded posture, byte-identical);
     * a positive integer = the per-arm floor; anything else fails closed —
     * never a silently-different posture.
     */
    static int floorForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        String trimmed = spec.trim();
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unknown BENCH_NOTES_FLOOR spec: '" + spec
                    + "' (expected a positive integer, e.g. 5) — fail-closed");
        }
        if (value < 0 || value > 10_000) {
            throw new IllegalStateException("BENCH_NOTES_FLOOR out of range: " + value
                    + " (expected 0..10000) — fail-closed");
        }
        return value;
    }

    /**
     * A floor &le; 0 returns the delegate ITSELF (absent-path byte-identity by
     * construction — never a wrapper).
     */
    public static RetrievalProvider maybeWrap(RetrievalProvider delegate, int notesFloor,
                                              Map<String, String> kindByRef) {
        if (notesFloor <= 0) {
            return delegate;
        }
        return new FloorProvider(delegate, notesFloor, kindByRef);
    }

    /** Portable evidence identity — the same shape {@code Run005C.refs()} records. */
    static String refOf(RetrievalCandidate candidate) {
        String ordinal = candidate.metadata().getOrDefault("chunk_index", "-1");
        return candidate.documentId() + ":" + ordinal;
    }

    private static final class FloorProvider implements RetrievalProvider {

        private final RetrievalProvider delegate;
        private final int floor;
        private final Map<String, String> kindByRef;

        FloorProvider(RetrievalProvider delegate, int floor, Map<String, String> kindByRef) {
            this.delegate = Objects.requireNonNull(delegate, "delegate provider");
            if (floor <= 0) {
                throw new IllegalArgumentException("floor must be > 0, got " + floor);
            }
            this.floor = floor;
            this.kindByRef = Objects.requireNonNull(kindByRef, "kindByRef");
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            List<RetrievalCandidate> base = delegate.retrieve(query);
            if (base.size() < query.limit()) {
                // The arm is exhausted at the recorded cut — nothing beyond it exists.
                return base;
            }
            Set<String> baseRefs = new HashSet<>();
            for (RetrievalCandidate c : base) {
                baseRefs.add(refOf(c));
            }
            List<RetrievalCandidate> out = new ArrayList<>(base);
            Set<String> appendedRefs = new HashSet<>();
            int appended = 0;
            int extension = floor;
            int lastProbeLimit = -1;
            while (appended < floor) {
                int probeLimit = query.limit() + extension;
                if (probeLimit == lastProbeLimit) {
                    break; // window capped without filling — record the under-fill honestly
                }
                lastProbeLimit = probeLimit;
                List<RetrievalCandidate> probed = delegate.retrieve(withLimit(query, probeLimit));
                boolean exhausted = probed.size() < probeLimit;
                // The probe is the SAME ranking at a deeper cut: every candidate not
                // already in the base list is a beyond-cut candidate, scanned in
                // arm-rank order (prefix instability, the g2-024 precedent, cannot
                // corrupt this — base membership is excluded by REF, not by position).
                for (RetrievalCandidate c : probed) {
                    if (appended >= floor) {
                        break;
                    }
                    String ref = refOf(c);
                    if (baseRefs.contains(ref) || appendedRefs.contains(ref)) {
                        continue;
                    }
                    if (!FLOOR_KIND.equals(kindByRef.get(ref))) {
                        continue;
                    }
                    out.add(c);
                    appendedRefs.add(ref);
                    appended++;
                }
                if (exhausted) {
                    break;
                }
                extension = Math.min(extension * 2, MAX_EXTENSION);
            }
            return out;
        }

        private static StructuredRetrievalQuery withLimit(StructuredRetrievalQuery q, int limit) {
            return new StructuredRetrievalQuery(q.normalizedQuery(), q.scope(),
                    q.specificationPointIds(), q.conceptIds(), q.misconceptionIds(),
                    q.resourceKinds(), q.learnerSignals(), q.evidence(), limit);
        }
    }
}
