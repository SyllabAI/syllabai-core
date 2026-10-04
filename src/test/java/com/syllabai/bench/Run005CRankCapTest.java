package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.BoundaryPolicy;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The T-C77 rank-bound seam: {@code BENCH_NOTES_RANK_CAP} applies the
 * TWO-ARM HORIZON PARTITION to the fused pool (RANK-CAP form, pre-registered
 * in RANK-BOUNDED-KIND-ADMISSION-PREREGISTRATION.md BEFORE any run). Absence
 * keeps the run-005-h path byte-identical by construction (the transform is
 * never built), unknown specs fail closed, the five-gate guard treats the
 * kind-arm + its bound as ONE lever (the bound REQUIRES the kind-arm, stays
 * exclusive with floor x reranker x weights), and the pinned partition
 * semantics hold: set-preservation, contribution-stripping exact against a
 * real {@link ReciprocalRankFusion} fusion (the grounding's 3596/3596 shape),
 * prefix by stripped two-arm score with chunk_ref ASC ties, tail by kind-arm
 * rank, honest under-fill recording, and the double-pass byte-identity the
 * bench determinism contract rides on.
 */
class Run005CRankCapTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.nameUUIDFromBytes("t-c77-rank-cap-test".getBytes()), "BENCH-TEST", Set.of());
    private static final String SEMANTIC_ID = "pgvector-stub";

    // ── spec parser ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("absent/blank spec -> 0 (the run-005-h posture, byte-identical)")
    void absentSpecIsZero() {
        assertThat(NotesRankCapRetrieval.rankCapForSpec(null)).isZero();
        assertThat(NotesRankCapRetrieval.rankCapForSpec("")).isZero();
        assertThat(NotesRankCapRetrieval.rankCapForSpec("   ")).isZero();
        assertThat(NotesRankCapRetrieval.rankCapForSpec("0")).isZero();
    }

    @Test
    @DisplayName("positive integer -> the protected horizon H (whitespace-tolerant)")
    void integerSpecParses() {
        assertThat(NotesRankCapRetrieval.rankCapForSpec("20")).isEqualTo(20);
        assertThat(NotesRankCapRetrieval.rankCapForSpec(" 20 ")).isEqualTo(20);
    }

    @Test
    @DisplayName("unknown/non-integer/out-of-range spec fails closed — never silently different")
    void unknownSpecFailsClosed() {
        assertThatThrownBy(() -> NotesRankCapRetrieval.rankCapForSpec("twenty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_NOTES_RANK_CAP");
        assertThatThrownBy(() -> NotesRankCapRetrieval.rankCapForSpec("lexical_precision"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesRankCapRetrieval.rankCapForSpec("-3"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesRankCapRetrieval.rankCapForSpec("10001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> NotesRankCapRetrieval.rankCapForSpec("10000")).doesNotThrowAnyException();
    }

    // ── the five-way one-lever guard ─────────────────────────────────────────

    @Test
    @DisplayName("the bound REQUIRES the kind-arm; the pair is one lever; the others stay exclusive")
    void fiveWayGuard() {
        // a bound with no bounded arm is a composition error — fail-closed
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 0, 0, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires BENCH_NOTES_KIND_ARM");
        // the recorded run-005-i posture: kind-arm + its bound, nothing else
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15, 20))
                .doesNotThrowAnyException();
        // the bound is exclusive with each of the other levers
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 5, 15, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> Run005C.requireSingleLever(new LexicalPrecisionReranker(),
                null, 0, 15, 20))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, Map.of(), 0, 15, 20))
                .isInstanceOf(IllegalStateException.class);
        // a bound that rides on ANOTHER lever's posture is equally excluded
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 5, 0, 20))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(new LexicalPrecisionReranker(),
                null, 0, 0, 20))
                .isInstanceOf(IllegalStateException.class);
        // the kind-arm alone and the absent posture stay legal (the h / pre-h paths)
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15, 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 0, 0))
                .doesNotThrowAnyException();
        // the older overloads keep their contracts (the T-C65/T-C69/T-C72 test shapes)
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 5, 15))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15)).doesNotThrowAnyException();
    }

    // ── absent-path identity ─────────────────────────────────────────────────

    @Test
    @DisplayName("gate absent -> the transform is never constructed (the h path byte-identical)")
    void absentPathNeverConstructsTheTransform() {
        assertThat(NotesRankCapRetrieval.maybeWrap(query -> List.of(), null, 0,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID))).isNull();
        assertThatThrownBy(() -> new NotesRankCapRetrieval(query -> List.of(),
                notesArm(List.of()), 0,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never construct");
    }

    @Test
    @DisplayName("the transform refuses to build without the bounded arm (the guard's second lock)")
    void transformRequiresTheBoundedArm() {
        assertThatThrownBy(() -> NotesRankCapRetrieval.maybeWrap(query -> List.of(), null, 20,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("BENCH_NOTES_RANK_CAP requires BENCH_NOTES_KIND_ARM");
    }

    // ── partition semantics on a real fusion ──────────────────────────────────

    @Test
    @DisplayName("contribution-stripping is exact against the real fuser; the kind-boosted ref drops back")
    void stripsTheKindArmContributionAgainstTheRealFuser() {
        RetrievalCandidate b = cand(SEMANTIC_ID, "docB", 0);   // two-arm only
        RetrievalCandidate a = cand(SEMANTIC_ID, "docA", 0);   // dual-listed: the dfed06678b shape
        RetrievalProvider semantic = provider(SEMANTIC_ID, List.of(b, a));
        NotesArmRetrieval arm = notesArm(
                List.of(notesRow("docA", 0), notesRow("docN1", 0), notesRow("docN2", 0)));

        ReciprocalRankFusion rrf = new ReciprocalRankFusion(60);
        RetrievalFabric fabricH = new RetrievalFabric(List.of(semantic, arm), rrf,
                BoundaryPolicy.allowAll());
        RetrievalFabric fabricC = new RetrievalFabric(List.of(semantic), rrf,
                BoundaryPolicy.allowAll());

        // the h-era order: the kind term lifts docA above docB
        List<String> hOrder = refs(fabricH.retrieve(query("learner question")));
        assertThat(hOrder).containsExactly("docA:0", "docB:0", "docN1:0", "docN2:0");

        NotesRankCapRetrieval rankCap = new NotesRankCapRetrieval(fabricH::retrieve, arm, 20,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));
        List<RetrievalFabric.FusedCandidate> out = rankCap.retrieve(query("learner question"));

        // prefix by STRIPPED two-arm score: docB (1/61) back above docA (1/62);
        // tail by kind-arm rank: docN1 (1/62) then docN2 (1/63)
        assertThat(refs(out)).containsExactly("docB:0", "docA:0", "docN1:0", "docN2:0");

        double bTwoArm = fusedScore(fabricC, "learner question", "docB:0");
        double aTwoArm = fusedScore(fabricC, "learner question", "docA:0");
        assertThat(out.get(0).fusedScore()).isEqualTo(bTwoArm);            // no kind term: bit-exact
        assertThat(out.get(1).fusedScore()).isCloseTo(aTwoArm, within(1e-12));
        // the fresh kind-arm-only refs pass through with their full fused score
        assertThat(out.get(2).fusedScore()).isEqualTo(1.0 / 62);
        assertThat(out.get(3).fusedScore()).isEqualTo(1.0 / 63);
        // the SET is preserved: same pool, re-partitioned
        assertThat(Set.copyOf(refs(out))).isEqualTo(Set.copyOf(hOrder));
    }

    @Test
    @DisplayName("prefix ties break chunk_ref ASC; the tail orders by kind-arm rank, not fused score")
    void pinnedTiebreaks() {
        NotesArmRetrieval arm = notesArm(
                List.of(notesRow("docN2", 0), notesRow("docN1", 0)));   // N2 = kind rank 0
        Map<String, List<RetrievalFabric.FusedCandidate>> byText = new HashMap<>();
        byText.put("learner question", List.of(
                // equal two-arm scores -> chunk_ref ASC decides (docX before docY)
                fused(cand(SEMANTIC_ID, "docY", 0), 1.0 / 61, SEMANTIC_ID),
                fused(cand(SEMANTIC_ID, "docX", 0), 1.0 / 61, SEMANTIC_ID),
                // tail: fused scores deliberately inverted vs the kind ranks
                fused(cand(NotesArmRetrieval.ARM_ID, "docN1", 0), 0.9, NotesArmRetrieval.ARM_ID),
                fused(cand(NotesArmRetrieval.ARM_ID, "docN2", 0), 0.5, NotesArmRetrieval.ARM_ID)));
        NotesRankCapRetrieval rankCap = new NotesRankCapRetrieval(
                query -> byText.get(query.normalizedQuery().strip()), arm, 20,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));

        assertThat(refs(rankCap.retrieve(query("learner question"))))
                .containsExactly("docX:0", "docY:0", "docN2:0", "docN1:0");
    }

    @Test
    @DisplayName("set-preservation + determinism: same pool re-partitioned, twice identical, one SQL")
    void setPreservationAndDeterminism() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotesArmRetrieval arm = notesArm(jdbc,
                List.of(notesRow("docA", 0), notesRow("docN1", 0)));
        Map<String, List<RetrievalFabric.FusedCandidate>> byText = new HashMap<>();
        byText.put("learner question", List.of(
                fused(cand(SEMANTIC_ID, "docA", 0), 1.0 / 62 + 1.0 / 61, SEMANTIC_ID,
                        NotesArmRetrieval.ARM_ID),
                fused(cand(SEMANTIC_ID, "docB", 3), 1.0 / 61, SEMANTIC_ID),
                fused(cand(NotesArmRetrieval.ARM_ID, "docN1", 0), 1.0 / 62,
                        NotesArmRetrieval.ARM_ID)));
        NotesRankCapRetrieval rankCap = new NotesRankCapRetrieval(
                query -> byText.get(query.normalizedQuery().strip()), arm, 20,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));

        List<RetrievalFabric.FusedCandidate> first = rankCap.retrieve(query("learner question"));
        List<RetrievalFabric.FusedCandidate> second = rankCap.retrieve(query("learner question"));
        assertThat(refs(first)).containsExactly("docB:3", "docA:0", "docN1:0");
        assertThat(first).isEqualTo(second);                       // double-pass byte-identity
        Set<String> pool = Set.of("docA:0", "docB:3", "docN1:0");
        assertThat(Set.copyOf(refs(first))).isEqualTo(pool);       // set-preservation
        // one SQL behind the two passes: the arm's list is memoized (O(1) re-read)
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class),
                any(Object[].class));
    }

    // ── under-fill: honest recording, never exclusion ─────────────────────────

    @Test
    @DisplayName("under-fill at a short two-arm surface is recorded honestly; the tail still serves")
    void underFillIsRecordedHonestly() {
        NotesArmRetrieval arm = notesArm(
                List.of(notesRow("docA", 0), notesRow("docN1", 0), notesRow("docN2", 0)));
        Map<String, List<RetrievalFabric.FusedCandidate>> byText = new HashMap<>();
        byText.put("learner question", List.of(
                fused(cand(SEMANTIC_ID, "docA", 0), 0.05, SEMANTIC_ID),
                fused(cand(SEMANTIC_ID, "docB", 0), 0.03, SEMANTIC_ID),
                fused(cand(NotesArmRetrieval.ARM_ID, "docN1", 0), 1.0 / 62,
                        NotesArmRetrieval.ARM_ID),
                fused(cand(NotesArmRetrieval.ARM_ID, "docN2", 0), 1.0 / 63,
                        NotesArmRetrieval.ARM_ID)));
        // a LONG two-arm surface: exactly H refs — the H boundary itself
        List<RetrievalFabric.FusedCandidate> longTwoArm = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            longTwoArm.add(fused(cand(SEMANTIC_ID, "docL" + i, 0), 1.0 - i * 0.001, SEMANTIC_ID));
        }
        byText.put("long two-arm question", longTwoArm);
        NotesRankCapRetrieval rankCap = new NotesRankCapRetrieval(
                query -> byText.get(query.normalizedQuery().strip()), arm, 20,
                BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));

        List<RetrievalFabric.FusedCandidate> out = rankCap.retrieve(query("learner question"));
        assertThat(out).hasSize(4);                     // nothing excluded — the tail still serves
        assertThat(refs(out)).startsWith("docA:0", "docB:0");
        assertThat(rankCap.underFilledQueries()).isEqualTo(1);
        // idempotent across the double-pass (tracked by stripped query text)
        rankCap.retrieve(query("learner question"));
        assertThat(rankCap.underFilledQueries()).isEqualTo(1);
        // a prefix of exactly H is NOT under-fill (the conditional guarantee holds)
        List<RetrievalFabric.FusedCandidate> longOut = rankCap.retrieve(query("long two-arm question"));
        assertThat(longOut).hasSize(20);
        assertThat(rankCap.underFilledQueries()).isEqualTo(1);
    }

    // ── dichotomy breaches fail closed ────────────────────────────────────────

    @Test
    @DisplayName("an unknown arm or an unranked kind-only ref is a composition drift — fail-closed")
    void dichotomyBreachFailsClosed() {
        NotesArmRetrieval arm = notesArm(List.of(notesRow("docA", 0)));
        NotesRankCapRetrieval rankCap = new NotesRankCapRetrieval(
                query -> List.of(fused(cand("mystery-arm", "docM", 0), 0.5, "mystery-arm")),
                arm, 20, BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));
        assertThatThrownBy(() -> rankCap.retrieve(query("learner question")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown arm");

        NotesRankCapRetrieval orphan = new NotesRankCapRetrieval(
                query -> List.of(fused(cand(NotesArmRetrieval.ARM_ID, "docZZ", 9), 0.5,
                        NotesArmRetrieval.ARM_ID)),
                arm, 20, BoundaryPolicy.allowAll(), Set.of(SEMANTIC_ID));
        assertThatThrownBy(() -> orphan.retrieve(query("learner question")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dichotomy breach");
    }

    // ── fakes (the Run005CKindArmTest house pattern) ──────────────────────────

    private static RetrievalCandidate cand(String providerId, String doc, int idx) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("chunk_index", String.valueOf(idx));
        return new RetrievalCandidate(providerId,
                UUID.nameUUIDFromBytes(("doc|" + doc).getBytes()),
                doc, 1,
                UUID.nameUUIDFromBytes(("chunk|" + doc + ":" + idx).getBytes()).toString(),
                null, null, "content " + doc + ":" + idx, 0.9, null,
                "gemini-embedding-001", metadata);
    }

    private static RetrievalFabric.FusedCandidate fused(RetrievalCandidate c, double score,
                                                        String... providers) {
        return new RetrievalFabric.FusedCandidate(c, score, List.of(providers));
    }

    private static RetrievalProvider provider(String id, List<RetrievalCandidate> out) {
        return new RetrievalProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
                return out;
            }
        };
    }

    private static List<String> refs(List<RetrievalFabric.FusedCandidate> fused) {
        return fused.stream()
                .map(fc -> fc.candidate().documentId() + ":"
                        + fc.candidate().metadata().getOrDefault("chunk_index", "-1"))
                .toList();
    }

    private static double fusedScore(RetrievalFabric fabric, String text, String ref) {
        return fabric.retrieve(query(text)).stream()
                .filter(fc -> refs(List.of(fc)).get(0).equals(ref))
                .findFirst()
                .orElseThrow()
                .fusedScore();
    }

    private static StructuredRetrievalQuery query(String text) {
        return StructuredRetrievalQuery.of(text, SCOPE, 40);
    }

    /** A real NotesArmRetrieval whose candidate SQL returns the canned notes rows. */
    private static NotesArmRetrieval notesArm(List<Map<String, Object>> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubRows(jdbc, rows);
        return new NotesArmRetrieval(jdbc, new RecordingEmbedding(new float[] {0.1f}), 15);
    }

    private static NotesArmRetrieval notesArm(JdbcTemplate jdbc, List<Map<String, Object>> rows) {
        stubRows(jdbc, rows);
        return new NotesArmRetrieval(jdbc, new RecordingEmbedding(new float[] {0.1f}), 15);
    }

    private static void stubRows(JdbcTemplate jdbc, List<Map<String, Object>> rows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<RetrievalCandidate> mapper = invocation.getArgument(1);
                    List<RetrievalCandidate> out = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        out.add(mapper.mapRow(resultSetFor(row), 0));
                    }
                    return out;
                });
    }

    private static Map<String, Object> notesRow(String documentId, int chunkIndex) {
        Map<String, Object> rs = new HashMap<>();
        rs.put("id", UUID.nameUUIDFromBytes(("chunk|" + documentId + ":" + chunkIndex).getBytes()));
        rs.put("document_row_id", UUID.nameUUIDFromBytes(("doc|" + documentId).getBytes()));
        rs.put("doc_version", 1);
        rs.put("document_id", documentId);
        rs.put("kind", "EXTERNAL_NOTES");
        rs.put("chunk_index", chunkIndex);
        rs.put("content", "content " + chunkIndex);
        rs.put("page_start", 1);
        rs.put("page_end", 2);
        rs.put("element_ids", "[]");
        rs.put("embedding_model", "gemini-embedding-001");
        rs.put("score", 0.5 - chunkIndex * 0.01);
        return rs;
    }

    private static java.sql.ResultSet resultSetFor(Map<String, Object> row)
            throws java.sql.SQLException {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn((UUID) row.get("id"));
        when(rs.getObject("document_row_id", UUID.class))
                .thenReturn((UUID) row.get("document_row_id"));
        when(rs.getInt("doc_version")).thenReturn((Integer) row.get("doc_version"));
        when(rs.getString("document_id")).thenReturn((String) row.get("document_id"));
        when(rs.getString("kind")).thenReturn((String) row.get("kind"));
        when(rs.getInt("chunk_index")).thenReturn((Integer) row.get("chunk_index"));
        when(rs.getString("content")).thenReturn((String) row.get("content"));
        when(rs.getObject("page_start")).thenReturn(row.get("page_start"));
        when(rs.getObject("page_end")).thenReturn(row.get("page_end"));
        when(rs.getString("element_ids")).thenReturn((String) row.get("element_ids"));
        when(rs.getString("embedding_model")).thenReturn((String) row.get("embedding_model"));
        when(rs.getDouble("score")).thenReturn((Double) row.get("score"));
        return rs;
    }

    private static final class RecordingEmbedding implements com.syllabai.content.EmbeddingProvider {
        private final float[] vector;

        RecordingEmbedding(float[] vector) {
            this.vector = vector;
        }

        @Override
        public String model() {
            return "fake-embedding";
        }

        @Override
        public int dimension() {
            return vector.length;
        }

        @Override
        public float[] embedQuery(String text) {
            return vector.clone();
        }

        @Override
        public float[] embedDocument(String text) {
            throw new IllegalStateException("not used");
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new IllegalStateException("not used");
        }
    }
}
