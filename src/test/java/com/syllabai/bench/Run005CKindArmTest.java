package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.EmbeddingProvider;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The T-C72 kind-arm seam: {@code BENCH_NOTES_KIND_ARM} adds a dedicated
 * EXTERNAL_NOTES fusion arm (KIND-ARM form, pre-registered in
 * ARM-COMPOSITION-PREREGISTRATION.md BEFORE any run). Absence keeps the
 * recorded two-provider fabric (byte-identical by construction — the arm is
 * never constructed), unknown specs fail closed, the four levers (reranker ×
 * weights × floor × kind-arm) are mutually exclusive, and the arm's pinned
 * semantics hold: the kind-scoped production candidate SQL with a
 * deterministic chunk_ref ASC tiebreak, the frozen query vector through the
 * production EmbeddingProvider port, rows passed through in SQL order
 * (determinism), kind exclusivity fail-closed, and under-fill recorded
 * honestly at kind-universe exhaustion.
 */
class Run005CKindArmTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.nameUUIDFromBytes("t-c72-kind-arm-test".getBytes()), "BENCH-TEST", Set.of());
    private static final UUID CV_ID = SCOPE.curriculumVersionId();

    // ── spec parser ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("absent/blank spec -> 0 (the recorded two-provider posture, byte-identical)")
    void absentSpecIsZero() {
        assertThat(NotesArmRetrieval.kindArmForSpec(null)).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("")).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("   ")).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("0")).isZero();
    }

    @Test
    @DisplayName("positive integer -> the kind-arm depth K (whitespace-tolerant)")
    void integerSpecParses() {
        assertThat(NotesArmRetrieval.kindArmForSpec("15")).isEqualTo(15);
        assertThat(NotesArmRetrieval.kindArmForSpec(" 15 ")).isEqualTo(15);
    }

    @Test
    @DisplayName("unknown/non-integer/out-of-range spec fails closed — never silently different")
    void unknownSpecFailsClosed() {
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("fifteen"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_NOTES_KIND_ARM");
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("lexical_precision"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("-3"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("10001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> NotesArmRetrieval.kindArmForSpec("10000")).doesNotThrowAnyException();
    }

    // ── four-way one-lever guard ─────────────────────────────────────────────

    @Test
    @DisplayName("one lever at a time: reranker x weights x floor x kind-arm — any pair is a composition error")
    void leversAreMutuallyExclusive() {
        EvidenceReranker reranker = new LexicalPrecisionReranker();
        Map<EvidenceItem.EvidenceSource, Double> weights = Map.of();
        // every pair among the four gates fails closed
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 0, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, null, 5, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, null, 0, 15))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, weights, 5, 0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, weights, 0, 15))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 5, 15))
                .isInstanceOf(IllegalStateException.class);
        // three active fails closed
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 5, 15))
                .isInstanceOf(IllegalStateException.class);
        // exactly one lever at a time is fine
        assertThatCode(() -> Run005C.requireSingleLever(reranker, null, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, weights, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 5, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 0)).doesNotThrowAnyException();
        // the recorded two-arg and three-arg guards still hold (the T-C65/T-C69 test contracts)
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, null, 5))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── absent-path identity (provider composition) ───────────────────────────

    @Test
    @DisplayName("no kind-arm -> exactly the recorded two-arm list; active -> the arm appends THIRD")
    void compositionAppendsTheKindArmThird() {
        RetrievalProvider semantic = new StubProvider("pgvector");
        RetrievalProvider lexical = new StubProvider("bm25");
        RetrievalProvider notesArm = new StubProvider(NotesArmRetrieval.ARM_ID);
        // absent path: the two-provider list, unchanged order, same instances
        assertThat(Run005C.composeArms(semantic, lexical, null))
                .containsExactly(semantic, lexical);
        // active path: an ordinary THIRD arm input, appended last
        assertThat(Run005C.composeArms(semantic, lexical, notesArm))
                .containsExactly(semantic, lexical, notesArm);
    }

    @Test
    @DisplayName("the kind-arm is never constructed at K <= 0 (absent path constructs nothing)")
    void zeroKNeverConstructsTheArm() {
        assertThatThrownBy(() -> new NotesArmRetrieval(mock(JdbcTemplate.class),
                fakeEmbedding(new float[] {0.1f}), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never construct");
        assertThatThrownBy(() -> new NotesArmRetrieval(mock(JdbcTemplate.class),
                fakeEmbedding(new float[] {0.1f}), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── SQL drift-guard (the ChunkVectorRepositoryDiagnoseTest house pattern) ──

    @Test
    @DisplayName("drift-guard: the kind-arm SQL is arm A's production candidate SQL + kind predicate + chunk_ref tiebreak")
    void candidateSqlMirrorsTheProductionCandidateSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc,
                fakeEmbedding(new float[] {0.1f}), 15);

        arm.retrieve(query("learner question", 40));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        String normalized = sql.getValue().replaceAll("\\s+", " ").trim();
        // the production candidate SQL's pins: distance operator + embedding
        // column + embed_rev stamp + BOTH T-C20 VALIDATED scope branches
        assertThat(normalized).contains("1 - (c.embedding <=> ?::vector) as score");
        assertThat(normalized).contains("c.embed_rev = ?");
        assertThat(normalized).contains("p.validation_state = 'VALIDATED'");
        assertThat(normalized).contains("d.validation_state = 'VALIDATED'");
        // the kind predicate (the lever)
        assertThat(normalized).contains("d.kind = 'EXTERNAL_NOTES'");
        // the deterministic chunk_ref ASC final tiebreak (the g2-024 guard)
        assertThat(normalized).contains("order by c.embedding <=> ?::vector,"
                + " d.document_id asc, c.chunk_index asc");
        assertThat(normalized).endsWith("limit ?");
        // bind order: vector literal, embed_rev, scope x2, vector literal, K
        assertThat(args.getValue()).hasSize(6);
        assertThat(args.getValue()[0]).isEqualTo("[0.1]");
        assertThat(args.getValue()[1])
                .isEqualTo(ChunkVectorRepository.CURRENT_EMBED_REV);
        assertThat(args.getValue()[2]).isEqualTo(CV_ID);
        assertThat(args.getValue()[3]).isEqualTo(CV_ID);
        assertThat(args.getValue()[4]).isEqualTo("[0.1]");
        assertThat(args.getValue()[5]).isEqualTo(15);
    }

    // ── selection determinism + kind exclusivity + under-fill (real mapper) ───

    @Test
    @DisplayName("kind-arm selection is deterministic: rows pass through in SQL order, twice identical")
    void selectionIsDeterministic() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMapperRows(jdbc, row("docA", 0), row("docA", 1), row("docA", 2));
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc,
                fakeEmbedding(new float[] {0.1f}), 15);

        List<RetrievalCandidate> first = arm.retrieve(query("learner question", 40));
        List<RetrievalCandidate> second = arm.retrieve(query("learner question", 40));
        assertThat(first).hasSize(3);
        assertThat(first).isEqualTo(second);
        // rows pass through in SQL order (distance ASC, chunk_ref ASC) — the
        // arm never reorders
        for (int i = 0; i < 3; i++) {
            assertThat(first.get(i).metadata().get("chunk_index")).isEqualTo(String.valueOf(i));
        }
    }

    @Test
    @DisplayName("kind exclusivity, fail-closed: a non-notes row means the predicate drifted")
    void kindExclusivityFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMapperRows(jdbc, row("docA", 0));
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc,
                fakeEmbedding(new float[] {0.1f}), 15);
        assertThat(arm.retrieve(query("learner question", 40))).hasSize(1);

        JdbcTemplate drifted = mock(JdbcTemplate.class);
        stubMapperRows(drifted, row("docA", 0, "QUESTION_PAPER"));
        NotesArmRetrieval driftedArm = new NotesArmRetrieval(drifted,
                fakeEmbedding(new float[] {0.1f}), 15);
        assertThatThrownBy(() -> driftedArm.retrieve(query("learner question", 40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kind predicate drifted");
        // the guard itself, directly
        assertThatCode(() -> NotesArmRetrieval.requireNotesKind("EXTERNAL_NOTES", "docA"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> NotesArmRetrieval.requireNotesKind("MARK_SCHEME", "docA"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("under-fill at kind-universe exhaustion is recorded honestly (idempotent across the double-pass)")
    void underFillIsRecordedHonestly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMapperRows(jdbc, row("docA", 0), row("docA", 1), row("docA", 2)); // 3 < K=15
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc,
                fakeEmbedding(new float[] {0.1f}), 15);

        List<RetrievalCandidate> out = arm.retrieve(query("learner question", 40));
        assertThat(out).hasSize(3); // the universe is exhausted: shorter list, honestly
        assertThat(arm.underFilledQueries()).isEqualTo(1);
        // the determinism double-pass re-runs the SAME query: idempotent
        arm.retrieve(query("learner question", 40));
        assertThat(arm.underFilledQueries()).isEqualTo(1);
        // a DIFFERENT query under-filling counts once
        arm.retrieve(query("another learner question", 40));
        assertThat(arm.underFilledQueries()).isEqualTo(2);
        // a query NOT under-filling never enters the ledger
        stubMapperRowList(jdbc, notesRows(15));
        arm.retrieve(query("a fully served question", 40));
        assertThat(arm.underFilledQueries()).isEqualTo(2);
    }

    // ── delegation (the frozen preload through the production port) ───────────

    @Test
    @DisplayName("the query vector comes from the production EmbeddingProvider port, stripped like the serving path")
    void queryVectorDelegatesThroughTheProductionPort() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMapperRows(jdbc, row("docA", 0));
        RecordingEmbedding embedding = fakeEmbedding(new float[] {0.25f});
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc, embedding, 15);

        arm.retrieve(query("  learner question  ", 40));

        assertThat(embedding.askedFor).containsExactly("learner question");
    }

    @Test
    @DisplayName("blank normalized query -> honest empty list, the embed port never polled")
    void blankQueryIsHonestEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RecordingEmbedding embedding = fakeEmbedding(new float[] {0.25f});
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc, embedding, 15);

        assertThat(arm.retrieve(query("   ", 40))).isEmpty();
        assertThat(embedding.askedFor).isEmpty();
    }

    @Test
    @DisplayName("an inconsistent query vector fails closed (the serving path's provider check)")
    void inconsistentVectorFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProvider embedding = new EmbeddingProvider() {
            @Override
            public String model() {
                return "fake";
            }

            @Override
            public int dimension() {
                return 768;
            }

            @Override
            public float[] embedQuery(String text) {
                return new float[] {0.1f}; // wrong dimension
            }

            @Override
            public float[] embedDocument(String text) {
                throw new IllegalStateException("not used");
            }

            @Override
            public List<float[]> embedDocuments(List<String> texts) {
                throw new IllegalStateException("not used");
            }
        };
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc, embedding, 15);
        assertThatThrownBy(() -> arm.retrieve(query("learner question", 40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent query vector");
    }

    @Test
    @DisplayName("rows map with the arm A adapter's metadata contract: portable ref reconstruction")
    void rowsCarryThePortableIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubMapperRows(jdbc, row("docA", 3));
        NotesArmRetrieval arm = new NotesArmRetrieval(jdbc,
                fakeEmbedding(new float[] {0.1f}), 15);

        List<RetrievalCandidate> out = arm.retrieve(query("learner question", 40));
        assertThat(out).hasSize(1);
        RetrievalCandidate c = out.get(0);
        assertThat(c.providerId()).isEqualTo(NotesArmRetrieval.ARM_ID);
        assertThat(c.documentId()).isEqualTo("docA");
        assertThat(c.metadata().get("chunk_index")).isEqualTo("3");
        assertThat(c.metadata().get("document_kind")).isEqualTo("EXTERNAL_NOTES");
        assertThat(c.metadata().get("page_start")).isEqualTo("1");
        assertThat(c.metadata().get("page_end")).isEqualTo("2");
        // the fabric's identity convention: the locator is the chunk UUID
        assertThat(c.evidenceLocator()).isNotBlank();
        assertThat(UUID.fromString(c.evidenceLocator())).isNotNull();
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    private static final class StubProvider implements RetrievalProvider {
        private final String id;

        StubProvider(String id) {
            this.id = id;
        }

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
            return List.of();
        }
    }

    private static final class RecordingEmbedding implements EmbeddingProvider {
        final List<String> askedFor = new ArrayList<>();
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
            askedFor.add(text);
            return vector.clone();
        }

        @Override
        public float[] embedDocument(String text) {
            throw new IllegalStateException("the kind-arm never embeds documents");
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new IllegalStateException("the kind-arm never embeds documents");
        }
    }

    private static RecordingEmbedding fakeEmbedding(float[] vector) {
        return new RecordingEmbedding(vector);
    }

    private static StructuredRetrievalQuery query(String text, int limit) {
        return StructuredRetrievalQuery.of(text, SCOPE, limit);
    }

    /**
     * Stubs jdbc.query to run the REAL row mapper over canned ResultSet rows —
     * the mapper's kind exclusivity and metadata contract are under test, not
     * bypassed.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    private static void stubMapperRows(JdbcTemplate jdbc,
                                       Map<String, Object>... cannedRows) {
        stubMapperRowList(jdbc, List.of(cannedRows));
    }

    private static void stubMapperRowList(JdbcTemplate jdbc,
                                          List<Map<String, Object>> cannedRows) {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<RetrievalCandidate> mapper = invocation.getArgument(1);
                    List<RetrievalCandidate> out = new ArrayList<>();
                    for (Map<String, Object> row : cannedRows) {
                        out.add(mapper.mapRow(resultSetFor(row), 0));
                    }
                    return out;
                });
    }

    private static List<Map<String, Object>> notesRows(int n) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row("docN", i));
        }
        return rows;
    }

    private static Map<String, Object> row(String documentId, int chunkIndex) {
        return row(documentId, chunkIndex, "EXTERNAL_NOTES");
    }

    private static Map<String, Object> row(String documentId, int chunkIndex, String kind) {
        Map<String, Object> rs = new HashMap<>();
        rs.put("id", UUID.nameUUIDFromBytes(("chunk|" + documentId + ":" + chunkIndex).getBytes()));
        rs.put("document_row_id", UUID.nameUUIDFromBytes(("doc|" + documentId).getBytes()));
        rs.put("doc_version", 1);
        rs.put("document_id", documentId);
        rs.put("kind", kind);
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
}
