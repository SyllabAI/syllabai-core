package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The T-C72 arm-composition seam: {@code BENCH_NOTES_KIND_ARM} adds a THIRD
 * fusion input — the dedicated EXTERNAL_NOTES kind-arm (the KIND-ARM form,
 * pre-registered in ARM-COMPOSITION-PREREGISTRATION.md BEFORE any run).
 * Absence creates NOTHING (the fabric's arm list stays the recorded
 * two-provider list, byte-identical by construction), unknown specs fail
 * closed, the FOUR levers (reranker × weights × floor × kind-arm) are
 * mutually exclusive, and the kind-arm's semantics hold: arm A's production
 * vector SQL shape (same distance operator, embedding column, embed_rev
 * stamp) kind-scoped over the embedded corpus, LIMIT K, fully ordered
 * (distance ASC, then the deterministic document_id/chunk_index tiebreak),
 * the same frozen query vector, under-fill returned honestly, the kind never
 * injectable, the scope mandatory (T-C07).
 */
class Run005CNotesKindArmTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.nameUUIDFromBytes("t-c72-kind-arm-test".getBytes()), "BENCH-TEST", Set.of());

    // ── spec parser ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("absent/blank spec -> 0 (the recorded posture, byte-identical)")
    void absentSpecIsZero() {
        assertThat(NotesArmRetrieval.kindArmForSpec(null)).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("")).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("   ")).isZero();
        assertThat(NotesArmRetrieval.kindArmForSpec("0")).isZero();
    }

    @Test
    @DisplayName("positive integer -> the kind-arm K (whitespace-tolerant)")
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
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("plan_v2"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("-3"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NotesArmRetrieval.kindArmForSpec("10001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> NotesArmRetrieval.kindArmForSpec("10000")).doesNotThrowAnyException();
    }

    // ── four-way one-lever guard ──────────────────────────────────────────────

    @Test
    @DisplayName("one lever at a time: reranker x weights x floor x kind-arm — any pair is a composition error")
    void leversAreMutuallyExclusive() {
        EvidenceReranker reranker = new LexicalPrecisionReranker();
        Map<EvidenceItem.EvidenceSource, Double> weights = Map.of();
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 0, 15))
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
        // triples and the quartet are equally impossible
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 5, 15))
                .isInstanceOf(IllegalStateException.class);
        // each lever ALONE passes; nothing set passes
        assertThatCode(() -> Run005C.requireSingleLever(reranker, null, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, weights, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 5, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
        assertThatCode(() -> Run005C.requireSingleLever(reranker, null, 0)).doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 5)).doesNotThrowAnyException();
    }

    // ── absent-path identity ──────────────────────────────────────────────────

    @Test
    @DisplayName("K <= 0 creates NOTHING — the fabric arm list stays the recorded two-provider list")
    void absentKindArmCreatesNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProvider frozen = frozen();
        assertThat(NotesArmRetrieval.maybeCreate(jdbc, frozen, 0)).isNull();
        assertThat(NotesArmRetrieval.maybeCreate(jdbc, frozen, -1)).isNull();
    }

    @Test
    @DisplayName("K > 0 creates the third arm with its registry identity")
    void kindArmIdentity() {
        RetrievalProvider arm = NotesArmRetrieval.maybeCreate(
                mock(JdbcTemplate.class), frozen(), 15);
        assertThat(arm).isNotNull();
        assertThat(arm.id()).isEqualTo("pgvector:EXTERNAL_NOTES");
        assertThat(arm.available()).isTrue();
    }

    // ── retrieval semantics (mocked jdbc + the frozen embedding port) ─────────

    @Test
    @DisplayName("runs arm A's vector SQL shape kind-scoped: the kind predicate is baked (never injectable), fully ordered, LIMIT K; the same frozen query vector")
    void runsTheKindScopedOrderedSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProvider frozen = frozen();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<RetrievalCandidate>>any(),
                any(Object[].class))).thenReturn(List.of());
        RetrievalProvider arm = NotesArmRetrieval.maybeCreate(jdbc, frozen, 15);

        arm.retrieve(StructuredRetrievalQuery.of("  learner question  ", SCOPE, 40));

        String expectedLiteral = toVectorLiteral(frozen.embedQuery("learner question"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // the exact production-shaped argument order: literal, embed_rev, scope, scope, literal, K
        verify(jdbc).query(sql.capture(), ArgumentMatchers.<RowMapper<RetrievalCandidate>>any(),
                eq(expectedLiteral),
                eq(ChunkVectorRepository.CURRENT_EMBED_REV),
                eq(SCOPE.curriculumVersionId()), eq(SCOPE.curriculumVersionId()),
                eq(expectedLiteral), eq(15));
        String executed = sql.getValue();
        assertThat(executed).contains("1 - (c.embedding <=> ?::vector) as score");
        assertThat(executed).contains("c.embed_rev = ?");
        assertThat(executed).contains("and d.kind = 'EXTERNAL_NOTES'");
        assertThat(executed).contains("s.curriculum_version_id = ?");
        assertThat(executed).contains("s2.curriculum_version_id = ?");
        assertThat(executed).doesNotContain("validation_state");
        assertThat(executed).contains("order by c.embedding <=> ?::vector, "
                + "d.document_id asc, c.chunk_index asc");
        assertThat(executed.stripTrailing()).endsWith("limit ?");
        // the frozen port served the SAME normalized text arm A embeds (stripped);
        // atLeastOnce: the test itself reads the vector once to pin the literal
        verify(frozen, atLeastOnce()).embedQuery("learner question");
    }

    @Test
    @DisplayName("maps rows in the RetrievalCandidate identity convention: chunk UUID = evidenceLocator, ordinal in metadata, portable ref reconstructs")
    void mapsRowsToPortableCandidates() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ArgumentCaptor<RowMapper<RetrievalCandidate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapper.capture(), any(Object[].class)))
                .thenAnswer(inv -> List.of());
        RetrievalProvider arm = NotesArmRetrieval.maybeCreate(jdbc, frozen(), 15);
        arm.retrieve(StructuredRetrievalQuery.of("learner question", SCOPE, 40));

        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        UUID docRowId = UUID.nameUUIDFromBytes("doc-row".getBytes());
        UUID chunkId = UUID.nameUUIDFromBytes("chunk".getBytes());
        when(rs.getObject("document_row_id", UUID.class)).thenReturn(docRowId);
        when(rs.getString("document_id")).thenReturn("c439b2b0ffff");
        when(rs.getInt("doc_version")).thenReturn(2);
        when(rs.getObject("id", UUID.class)).thenReturn(chunkId);
        when(rs.getString("kind")).thenReturn("EXTERNAL_NOTES");
        when(rs.getInt("chunk_index")).thenReturn(3);
        when(rs.getString("content")).thenReturn("ionic bonding notes");
        when(rs.getObject("page_start")).thenReturn(7);
        when(rs.getObject("page_end")).thenReturn(9);
        when(rs.getString("element_ids")).thenReturn("[\"e1\",\"e2\"]");
        when(rs.getString("embedding_model")).thenReturn("gemini-embedding-001");
        when(rs.getDouble("score")).thenReturn(0.6123);

        RetrievalCandidate c = mapper.getValue().mapRow(rs, 0);
        assertThat(c).isNotNull();
        assertThat(c.providerId()).isEqualTo("pgvector:EXTERNAL_NOTES");
        assertThat(c.documentRowId()).isEqualTo(docRowId);
        assertThat(c.documentId()).isEqualTo("c439b2b0ffff");
        assertThat(c.evidenceLocator()).isEqualTo(chunkId.toString());
        assertThat(c.content()).isEqualTo("ionic bonding notes");
        assertThat(c.providerScore()).isEqualTo(0.6123);
        assertThat(c.embeddingModel()).isEqualTo("gemini-embedding-001");
        assertThat(c.metadata().get("chunk_index")).isEqualTo("3");
        assertThat(c.metadata().get("document_kind")).isEqualTo("EXTERNAL_NOTES");
        assertThat(c.metadata().get("page_start")).isEqualTo("7");
        assertThat(c.metadata().get("page_end")).isEqualTo("9");
        assertThat(c.metadata().get("element_ids")).isEqualTo("e1,e2");
        assertThat(NotesArmRetrieval.refOf(c)).isEqualTo("c439b2b0ffff:3");
    }

    @Test
    @DisplayName("under-fills honestly at universe exhaustion (fewer rows than K come back as-is)")
    void underFillReturnsWhatTheUniverseGave() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProvider frozen = frozen();
        // the under-fill contract is the SQL's LIMIT K with the returned list
        // passed through UNTOUCHED — assert exactly that passthrough.
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<RetrievalCandidate>>any(),
                any(Object[].class))).thenAnswer(inv -> {
                    RowMapper<RetrievalCandidate> m = inv.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getObject("document_row_id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getString("document_id")).thenReturn("docA");
                    when(rs.getInt("doc_version")).thenReturn(1);
                    when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    when(rs.getString("kind")).thenReturn("EXTERNAL_NOTES");
                    when(rs.getInt("chunk_index")).thenReturn(0);
                    when(rs.getString("content")).thenReturn("note");
                    when(rs.getObject("page_start")).thenReturn(null);
                    when(rs.getObject("page_end")).thenReturn(null);
                    when(rs.getString("element_ids")).thenReturn(null);
                    when(rs.getString("embedding_model")).thenReturn(null);
                    when(rs.getDouble("score")).thenReturn(0.55);
                    return List.of(m.mapRow(rs, 0), m.mapRow(rs, 1), m.mapRow(rs, 2));
                });
        List<RetrievalCandidate> out = NotesArmRetrieval.maybeCreate(jdbc, frozen, 15)
                .retrieve(StructuredRetrievalQuery.of("learner question", SCOPE, 40));
        assertThat(out).hasSize(3); // the universe gave 3 < K=15: returned as-is, recorded honestly
        assertThat(out.stream().map(NotesArmRetrieval::refOf)).containsExactly(
                "docA:0", "docA:0", "docA:0");
    }

    @Test
    @DisplayName("scope is enforced by the typed query itself (T-C07, constructor fail-closed); a blank query degrades to empty")
    void scopeMandatoryAndBlankQueryEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingProvider frozen = frozen();
        RetrievalProvider arm = NotesArmRetrieval.maybeCreate(jdbc, frozen, 15);
        // the typed query makes an unscoped retrieval UNREPRESENTABLE — the
        // constructor fails closed before any provider can see a null scope
        // (the seam's own null-scope guard stays as defense-in-depth)
        assertThatThrownBy(() -> new StructuredRetrievalQuery(
                "learner question", null, null, null, null, null, null, null, 40))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("retrieval never runs unscoped");
        assertThat(arm.retrieve(StructuredRetrievalQuery.of("   ", SCOPE, 40))).isEmpty();
    }

    // ── fakes/stubs ───────────────────────────────────────────────────────────

    private static EmbeddingProvider frozen() {
        EmbeddingProvider p = mock(EmbeddingProvider.class);
        when(p.model()).thenReturn("frozen-test");
        when(p.dimension()).thenReturn(768);
        when(p.embedQuery(anyString())).thenReturn(new float[768]);
        return p;
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }
}
