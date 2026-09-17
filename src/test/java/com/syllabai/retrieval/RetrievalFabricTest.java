package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retrieval fabric orchestrator (T-C13 arm C unlock): explicit composition,
 * the ONE central boundary exclusion applied pre-fusion, rank-only fusion via
 * the shipped ReciprocalRankFusion, identity dedup across arms, honest
 * emptiness, fail-closed contract violations.
 */
class RetrievalFabricTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());

    private static final UUID CHUNK_A =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CHUNK_B =
            UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID CHUNK_C =
            UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static RetrievalCandidate chunk(String providerId, UUID chunkId, String documentId,
                                            int chunkIndex, double score, String kind) {
        return new RetrievalCandidate(providerId, null, documentId, 1,
                chunkId.toString(), null, null, "content of " + chunkId, score,
                null, null,
                Map.of("chunk_index", String.valueOf(chunkIndex), "document_kind", kind));
    }

    /** Scripted provider: returns the queued list once per retrieve call. */
    private static final class ScriptedProvider implements RetrievalProvider {
        private final String id;
        private final boolean available;
        private final List<RetrievalCandidate> script;

        ScriptedProvider(String id, List<RetrievalCandidate> script) {
            this(id, true, script);
        }

        ScriptedProvider(String id, boolean available, List<RetrievalCandidate> script) {
            this.id = id;
            this.available = available;
            this.script = script;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            return script;
        }
    }

    private static StructuredRetrievalQuery query(int limit) {
        return StructuredRetrievalQuery.of("why does concentration affect rate", SCOPE, limit);
    }

    @Test
    @DisplayName("fuses two arms with the shipped RRF: agreement ranks first, fused scores present")
    void fusesTwoArmsWithAgreementBoost() {
        // Arm 1 ranks A > B; arm 2 ranks B > A. RRF (k=60) rewards the agreement:
        // both appear in both lists, A wins via rank-1 + rank-2 vs B's rank-2 + rank-1
        // — identical sums, so the deterministic tiebreak (source, then stable key)
        // decides; either way BOTH outrank C (present in only one list).
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("pgvector", List.of(
                                chunk("pgvector", CHUNK_A, "doc-1", 0, 0.9, "QUESTION_PAPER"),
                                chunk("pgvector", CHUNK_B, "doc-2", 1, 0.8, "MARK_SCHEME"))),
                        new ScriptedProvider("bm25", List.of(
                                chunk("bm25", CHUNK_B, "doc-2", 1, 0.7, "MARK_SCHEME"),
                                chunk("bm25", CHUNK_A, "doc-1", 0, 0.6, "QUESTION_PAPER"),
                                chunk("bm25", CHUNK_C, "doc-3", 2, 0.5, "QUESTION_PAPER")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(query(5));

        assertThat(fused).hasSize(3);
        // A and B tie on RRF sum (1/61+1/62 each); the deterministic tiebreak
        // (source ordinal: MARK_SCHEME 0 < QUESTION_PAPER 1) puts B first.
        assertThat(fused.get(0).candidate().documentId()).isEqualTo("doc-2");
        assertThat(fused.get(1).candidate().documentId()).isEqualTo("doc-1");
        double ab = Math.min(fused.get(0).fusedScore(), fused.get(1).fusedScore());
        assertThat(ab).isGreaterThan(fused.get(2).fusedScore());
        // attribution: both arms contributed to A and B; only bm25 to C
        assertThat(fused.get(0).providers()).hasSize(2);
        assertThat(fused.get(1).providers()).hasSize(2);
        assertThat(fused.get(2).providers()).containsExactly("bm25");
        // native provider scores survive untouched (rank-only fusion never edits
        // them) and the FIRST arm to emit an identity is its primary source
        assertThat(fused.get(0).candidate().providerScore()).isEqualTo(0.8);
        assertThat(fused.get(1).candidate().providerScore()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("same candidate from two arms deduplicates to ONE fused entry")
    void deduplicatesAcrossArms() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("pgvector", List.of(
                                chunk("pgvector", CHUNK_A, "doc-1", 0, 0.9, "QUESTION_PAPER"))),
                        new ScriptedProvider("bm25", List.of(
                                chunk("bm25", CHUNK_A, "doc-1", 0, 0.5, "QUESTION_PAPER")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(query(5));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).providers()).containsExactly("pgvector", "bm25");
        // the candidate identity is the first arm's (tracked once by locator)
        assertThat(fused.get(0).candidate().providerScore()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("central boundary policy excludes PRE-fusion: exclusion shifts ranks within each arm")
    void boundaryExcludesPreFusion() {
        // Arm 1: A(VALIDATED) rank1, X(SUGGESTED paper) rank2, B(VALIDATED) rank3.
        // Arm 2: B rank1, A rank2.
        // PRE-fusion exclusion: arm1 = [A, B] -> A 1/61 + B 1/62; arm2 = [B, A]
        //   -> B 1/61 + A 1/62; sums TIE -> deterministic source tiebreak
        //   (MARK_SCHEME ordinal 0 < QUESTION_PAPER ordinal 1) puts B first.
        // POST-fusion exclusion (the wrong semantics): fused ranks keep X's slot,
        //   A = 1/61+1/62 = 0.032522 > B = 1/63+1/61 = 0.032266 -> A first.
        // The asserted order therefore PROVES the exclusion happened pre-fusion.
        RetrievalCandidate validatedA = chunk("pgvector", CHUNK_A, "doc-ok-1", 0, 0.9, "QUESTION_PAPER");
        RetrievalCandidate suggestedX = new RetrievalCandidate("pgvector", null, "doc-sug", 1,
                CHUNK_C.toString(), null, null, "suggested content", 0.85, null, null,
                Map.of("chunk_index", "5", "document_kind", "QUESTION_PAPER"));
        RetrievalCandidate validatedB = chunk("bm25", CHUNK_B, "doc-ok-2", 1, 0.7, "MARK_SCHEME");
        RetrievalCandidate validatedA2 = chunk("bm25", CHUNK_A, "doc-ok-1", 0, 0.6, "QUESTION_PAPER");

        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("pgvector", List.of(validatedA, suggestedX, validatedB)),
                        new ScriptedProvider("bm25", List.of(validatedB, validatedA2))),
                new ReciprocalRankFusion(60),
                candidate -> !"doc-sug".equals(candidate.documentId()));

        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(query(5));

        assertThat(fused).hasSize(2);
        assertThat(fused).allSatisfy(f ->
                assertThat(f.candidate().documentId()).isNotEqualTo("doc-sug"));
        // B first = the pre-fusion world (post-fusion would rank A first)
        assertThat(fused.get(0).candidate().documentId()).isEqualTo("doc-ok-2");
        assertThat(fused.get(1).candidate().documentId()).isEqualTo("doc-ok-1");
        // attribution survives dedup: B appears in arm 1 (rank 3) and arm 2
        // (rank 1) under the same locator — attributed to BOTH providers
        assertThat(fused.get(0).providers()).containsExactly("pgvector", "bm25");
        assertThat(fused.get(1).providers()).containsExactly("pgvector", "bm25");
    }

    @Test
    @DisplayName("unavailable provider is skipped without being polled")
    void unavailableProviderSkipped() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("gemini-file-search", false,
                                List.of(chunk("gemini-file-search", CHUNK_A, "doc-1", 0, 0.9, "OTHER"))),
                        new ScriptedProvider("bm25", List.of(
                                chunk("bm25", CHUNK_B, "doc-2", 1, 0.5, "OTHER")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(query(5));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).candidate().evidenceLocator()).isEqualTo(CHUNK_B.toString());
    }

    @Test
    @DisplayName("honest emptiness: no arms yield candidates -> empty, never null")
    void emptyWhenNothingMatches() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("pgvector", List.of()),
                        new ScriptedProvider("bm25", List.of())),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());
        assertThat(fabric.retrieve(query(5))).isEmpty();
    }

    @Test
    @DisplayName("provider returning null is a fail-closed contract violation")
    void nullProviderResultFailsClosed() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("pgvector", null)),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());
        assertThatThrownBy(() -> fabric.retrieve(query(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null candidates");
    }

    @Test
    @DisplayName("non-UUID chunk locator is an identity-convention breach (fail-closed)")
    void nonUuidLocatorFailsClosed() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("fs", List.of(
                        new RetrievalCandidate("fs", null, "doc-x", 1, "segment-7", null, null,
                                "text", 0.5, null, null, Map.of())))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());
        assertThatThrownBy(() -> fabric.retrieve(query(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-convention breach");
    }

    @Test
    @DisplayName("determinism: identical inputs fuse identically (byte-stable order)")
    void deterministicFusion() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(
                        new ScriptedProvider("pgvector", List.of(
                                chunk("pgvector", CHUNK_A, "doc-1", 0, 0.9, "QUESTION_PAPER"),
                                chunk("pgvector", CHUNK_B, "doc-2", 1, 0.8, "MARK_SCHEME"))),
                        new ScriptedProvider("bm25", List.of(
                                chunk("bm25", CHUNK_B, "doc-2", 1, 0.7, "MARK_SCHEME"),
                                chunk("bm25", CHUNK_A, "doc-1", 0, 0.6, "QUESTION_PAPER")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        List<String> first = fabric.retrieve(query(5)).stream()
                .map(f -> f.candidate().evidenceLocator()).toList();
        for (int i = 0; i < 5; i++) {
            List<String> again = fabric.retrieve(query(5)).stream()
                    .map(f -> f.candidate().evidenceLocator()).toList();
            assertThat(again).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("construction rejects nulls")
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new RetrievalFabric(null, new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetrievalFabric(List.of(), null,
                BoundaryPolicy.allowAll())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetrievalFabric(List.of(), new ReciprocalRankFusion(60),
                null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetrievalFabric(
                new java.util.ArrayList<>(List.of((RetrievalProvider) null)),
                new ReciprocalRankFusion(60), BoundaryPolicy.allowAll()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("empty composition is an honest empty fabric")
    void emptyFabricReturnsEmpty() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(), new ReciprocalRankFusion(60), BoundaryPolicy.allowAll());
        assertThat(fabric.retrieve(query(5))).isEmpty();
    }
}
