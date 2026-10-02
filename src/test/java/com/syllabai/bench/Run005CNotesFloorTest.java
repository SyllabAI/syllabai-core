package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The T-C69 notes-floor seam: {@code BENCH_NOTES_FLOOR} arms each provider
 * with the per-arm EXTERNAL_NOTES floor (the per-kind quota lever in FLOOR
 * form, pre-registered in POOL-COMPOSITION-PREREGISTRATION.md BEFORE any
 * run). Absence keeps the bare provider (byte-identical by construction),
 * unknown specs fail closed, the three levers (reranker × weights × floor)
 * are mutually exclusive, and the floor's admission semantics hold: append
 * only EXTERNAL_NOTES candidates beyond the recorded cut, in arm-rank order,
 * never duplicate a base member, under-fill honestly at exhaustion, and probe
 * deeper (deterministic doubling) when the first window is short.
 */
class Run005CNotesFloorTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.nameUUIDFromBytes("t-c69-notes-floor-test".getBytes()), "BENCH-TEST", Set.of());

    // ── spec parser ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("absent/blank spec -> 0 (the recorded posture, byte-identical)")
    void absentSpecIsZero() {
        assertThat(NotesFloorRetrieval.floorForSpec(null)).isZero();
        assertThat(NotesFloorRetrieval.floorForSpec("")).isZero();
        assertThat(NotesFloorRetrieval.floorForSpec("   ")).isZero();
        assertThat(NotesFloorRetrieval.floorForSpec("0")).isZero();
    }

    @Test
    @DisplayName("positive integer -> the floor value (whitespace-tolerant)")
    void integerSpecParses() {
        assertThat(NotesFloorRetrieval.floorForSpec("5")).isEqualTo(5);
        assertThat(NotesFloorRetrieval.floorForSpec(" 12 ")).isEqualTo(12);
    }

    @Test
    @DisplayName("unknown/non-integer/out-of-range spec fails closed — never silently different")
    void unknownSpecFailsClosed() {
        assertThatThrownBy(() -> NotesFloorRetrieval.floorForSpec("five"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_NOTES_FLOOR");
        assertThatThrownBy(() -> NotesFloorRetrieval.floorForSpec("lexical_precision"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesFloorRetrieval.floorForSpec("-3"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesFloorRetrieval.floorForSpec("10001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> NotesFloorRetrieval.floorForSpec("10000")).doesNotThrowAnyException();
    }

    // ── three-way one-lever guard ─────────────────────────────────────────────

    @Test
    @DisplayName("one lever at a time: reranker x weights x floor — any pair is a composition error")
    void leversAreMutuallyExclusive() {
        EvidenceReranker reranker = new LexicalPrecisionReranker();
        Map<EvidenceItem.EvidenceSource, Double> weights = Map.of();
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, null, 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, weights, 5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 0))
                .isInstanceOf(IllegalStateException.class);
        // exactly one lever at a time is fine
        assertThatCode(() -> Run005C.requireSingleLever(reranker, null, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, weights, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 5)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0)).doesNotThrowAnyException();
        // the recorded two-arg guard still holds (the T-C65 test contract)
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── absent-path identity ──────────────────────────────────────────────────

    @Test
    @DisplayName("floor <= 0 returns the DELEGATE ITSELF — never a wrapper (absent-path byte-identity)")
    void absentFloorIsTheBareProvider() {
        RetrievalProvider bare = new FakeProvider(List.of());
        assertThat(NotesFloorRetrieval.maybeWrap(bare, 0, Map.of())).isSameAs(bare);
        assertThat(NotesFloorRetrieval.maybeWrap(bare, -1, Map.of())).isSameAs(bare);
    }

    // ── floor semantics ───────────────────────────────────────────────────────

    @Test
    @DisplayName("appends exactly N notes beyond the cut, in arm-rank order; the base cut is untouched")
    void appendsNotesBeyondTheCut() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            universe.add(cand("docA", i, "QUESTION_PAPER"));
        }
        for (int i = 40; i < 50; i++) {
            universe.add(cand("docA", i, "EXTERNAL_NOTES"));
        }
        for (int i = 50; i < 60; i++) {
            universe.add(cand("docA", i, "MARK_SCHEME"));
        }
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(
                new FakeProvider(universe), 5, kinds(universe));
        List<RetrievalCandidate> out = wrapped.retrieve(query(40));
        assertThat(out).hasSize(45);
        for (int i = 0; i < 40; i++) {
            assertThat(NotesFloorRetrieval.refOf(out.get(i)))
                    .isEqualTo("docA:" + i); // the recorded cut, order preserved
        }
        for (int i = 40; i < 45; i++) {
            assertThat(NotesFloorRetrieval.refOf(out.get(i))).isEqualTo("docA:" + i);
        }
    }

    @Test
    @DisplayName("a note already in the base list is never duplicated")
    void skipsNotesAlreadyInTheBase() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 39; i++) {
            universe.add(cand("docA", i, "QUESTION_PAPER"));
        }
        universe.add(cand("docA", 39, "EXTERNAL_NOTES")); // inside the base cut
        for (int i = 40; i < 44; i++) {
            universe.add(cand("docA", i, "EXTERNAL_NOTES")); // 4 beyond the cut
        }
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(
                new FakeProvider(universe), 5, kinds(universe));
        List<RetrievalCandidate> out = wrapped.retrieve(query(40));
        assertThat(out).hasSize(44); // 40 base + 4 appended, no duplicate of docA:39
        assertThat(out.stream().map(NotesFloorRetrieval::refOf).distinct()).hasSize(44);
    }

    @Test
    @DisplayName("under-fills honestly at arm exhaustion (fewer notes than the floor)")
    void underFillsAtExhaustion() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            universe.add(cand("docA", i, "QUESTION_PAPER"));
        }
        universe.add(cand("docA", 40, "EXTERNAL_NOTES"));
        universe.add(cand("docA", 41, "EXTERNAL_NOTES"));
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(
                new FakeProvider(universe), 5, kinds(universe));
        List<RetrievalCandidate> out = wrapped.retrieve(query(40));
        assertThat(out).hasSize(42); // the arm is exhausted: 2 appended, recorded as the under-fill
    }

    @Test
    @DisplayName("probes deeper (deterministic doubling) when the first window is short of notes")
    void probesDeeperWhenTheWindowIsShort() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            universe.add(cand("docA", i, "QUESTION_PAPER"));
        }
        for (int i = 40; i < 45; i++) {
            universe.add(cand("docA", i, "MARK_SCHEME")); // fills the first probe window
        }
        for (int i = 45; i < 50; i++) {
            universe.add(cand("docA", i, "EXTERNAL_NOTES")); // found only at limit+10
        }
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(
                new FakeProvider(universe), 5, kinds(universe));
        List<RetrievalCandidate> out = wrapped.retrieve(query(40));
        assertThat(out).hasSize(45);
        for (int i = 45; i < 50; i++) {
            assertThat(NotesFloorRetrieval.refOf(out.get(40 + i - 45))).isEqualTo("docA:" + i);
        }
    }

    @Test
    @DisplayName("only EXTERNAL_NOTES is admitted — other kinds beyond the cut are skipped")
    void onlyTheFloorKindIsAdmitted() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            universe.add(cand("docA", i, "QUESTION_PAPER"));
        }
        universe.add(cand("docA", 40, "MARK_SCHEME"));
        universe.add(cand("docA", 41, "EXTERNAL_NOTES"));
        universe.add(cand("docA", 42, "QUESTION_PAPER"));
        universe.add(cand("docA", 43, "EXTERNAL_NOTES"));
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(
                new FakeProvider(universe), 5, kinds(universe));
        List<RetrievalCandidate> out = wrapped.retrieve(query(40));
        assertThat(out).hasSize(42); // notes at 41 and 43 only; exhaustion stops the probes
        assertThat(out.stream().skip(40).map(NotesFloorRetrieval::refOf))
                .containsExactly("docA:41", "docA:43");
    }

    @Test
    @DisplayName("provider identity and availability delegate through")
    void identityDelegatesThrough() {
        List<RetrievalCandidate> universe = List.of(cand("docA", 0, "EXTERNAL_NOTES"));
        FakeProvider bare = new FakeProvider(universe);
        RetrievalProvider wrapped = NotesFloorRetrieval.maybeWrap(bare, 5, kinds(universe));
        assertThat(wrapped.id()).isEqualTo(bare.id());
        assertThat(wrapped.available()).isTrue();
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    /** A deterministic prefix-stable arm: the universe IS the ranking. */
    private static final class FakeProvider implements RetrievalProvider {
        private final List<RetrievalCandidate> universe;

        FakeProvider(List<RetrievalCandidate> universe) {
            this.universe = List.copyOf(universe);
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            return universe.subList(0, Math.min(query.limit(), universe.size()));
        }
    }

    private static StructuredRetrievalQuery query(int limit) {
        return StructuredRetrievalQuery.of("learner question", SCOPE, limit);
    }

    private static RetrievalCandidate cand(String docId, int ordinal, String kind) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("chunk_index", String.valueOf(ordinal));
        metadata.put("test_kind", kind);
        return new RetrievalCandidate("fake", null, docId, 1, docId + ":" + ordinal,
                null, null, "content " + ordinal, 1.0 / (ordinal + 1), null, null, metadata);
    }

    private static Map<String, String> kinds(List<RetrievalCandidate> universe) {
        Map<String, String> kinds = new HashMap<>();
        for (RetrievalCandidate c : universe) {
            kinds.put(NotesFloorRetrieval.refOf(c), c.metadata().get("test_kind"));
        }
        return kinds;
    }
}
