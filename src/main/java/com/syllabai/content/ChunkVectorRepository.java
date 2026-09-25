package com.syllabai.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the pgvector column of {@code document_chunks} (T-013). JPA cannot map
 * {@code vector(768)}, so embeddings are written and searched here with plain SQL
 * and explicit {@code ?::vector} casts — the same JdbcTemplate-meets-native-SQL
 * posture the knowledge-graph recursive CTEs use.
 *
 * <p>Embed-revision read filter (V33, plan §6): {@link #CURRENT_EMBED_REV} is the
 * single constant deciding which corpus generation serves. rev1 rows (the 2,333
 * legacy glmocr chunks) carry embed_rev = 1 and remain in place untouched; the
 * corpus-v2 ingest writes embed_rev = 2 rows and the cut-over is THIS constant
 * flipping to 2 — rollback is flipping it back. rev1 rows are never mutated in
 * place; they are deleted at the R5 cut-over once the eval gate passes.</p>
 */
@Repository
public class ChunkVectorRepository {

    /**
     * The corpus generation that serves (plan §6). 2 = corpus-v2 (atom-aligned,
     * header-stamped, metadata-complete bridge ingest — the R3 corpus-v2 leg).
     * FLIPPED 2026-09-20 (Task 32) after the offline eval gates passed on the
     * frozen embed-bridge-v2 substrate: (G1) rev2 hit@10 9/9 vs rev1 0/9 on the
     * rev2-covered gold subset, offline rev1 recomputation reconciled EXACTLY
     * with CI run-004-a-r3 (FETCH 0/40; only the 4 enumerate_paper queries hit);
     * (G2) 10/10 topical probes keyword-matched in rev2 top-10; (G3) 300/300
     * chunks header+group-key complete. rev1 rows (2,333 legacy chunks) are
     * never mutated in place — rollback is flipping this constant back to 1;
     * rev1 retirement (deletion) stays gated at R5.
     */
    public static final int CURRENT_EMBED_REV = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ChunkVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Persists one embedding; returns rows updated (0 if the chunk vanished). */
    public int storeEmbedding(UUID chunkId, float[] vector, String model) {
        return jdbc.update("""
                update document_chunks
                set embedding = ?::vector, embedding_model = ?, embedded_at = now()
                where id = ?
                """, toVectorLiteral(vector), model, chunkId);
    }

    /**
     * Cosine nearest-neighbour search over embedded chunks. {@code kind == null}
     * searches all document kinds; results are ordered by cosine distance ascending
     * (best first). NULL embeddings are never matched. The kind filter is folded
     * into the SQL text (not a nullable bind parameter) so Postgres never sees an
     * untyped NULL.
     *
     * <p>T-C07 (mandatory curriculum scoping): {@code curriculumVersionId} is
     * required — an EXISTS predicate narrows candidacy to chunks whose document
     * is a question paper or mark scheme of an exam paper whose subject belongs
     * to this curriculum version (the DB-verified join path
     * {@code document_chunks → documents(document_row_id) → exam_papers
     * (question/mark_scheme_document_id = documents.document_id) → subjects
     * (subject_id) → curriculum_versions}). OR — the V33 subject-branch — the
     * chunk itself carries {@code subject_id} resolving into the same curriculum
     * version, which is how the knowledge layer (notes/spec/textbook chunks with
     * no exam-paper row) becomes servable. Both branches fail closed: chunks
     * with NULL subject_id and no paper link resolve to no curriculum and are
     * never served. The curriculum id is a bound parameter (never SQL text).</p>
     *
     * <p>Every row is also filtered to {@code embed_rev = CURRENT_EMBED_REV} so
     * two corpus generations never blend inside one result set (plan §6
     * supersession rule — RRF must never see both).</p>
     */
    public List<ChunkHit> search(float[] queryVector, Document.Kind kind, UUID curriculumVersionId, int limit) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException(
                    "curriculumVersionId is mandatory — chunk search never runs unscoped (T-C07)");
        }
        String literal = toVectorLiteral(queryVector);
        String kindFilter = kind == null ? "" : "and d.kind = '" + kind.name() + "'\n";
        String sql = """
                select c.id, c.document_row_id, d.document_id, d.kind, c.chunk_index,
                       c.content, c.page_start, c.page_end, c.element_ids,
                       c.embedding_model, 1 - (c.embedding <=> ?::vector) as score
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is not null
                  and c.embed_rev = ?
                  and (
                        exists (
                              select 1 from exam_papers p
                              join subjects s on s.id = p.subject_id
                              where s.curriculum_version_id = ?
                                and (p.question_paper_document_id = d.document_id
                                  or p.mark_scheme_document_id = d.document_id))
                     or exists (
                              select 1 from subjects s2
                              where s2.curriculum_version_id = ?
                                and s2.id = c.subject_id))
                """ + kindFilter + """
                order by c.embedding <=> ?::vector
                limit ?
                """;
        return jdbc.query(sql,
                (rs, i) -> mapHit(rs),
                literal, CURRENT_EMBED_REV, curriculumVersionId, curriculumVersionId,
                literal, limit);
    }

    /**
     * Serving-eligible vector search (T-C20, the vector mirror of
     * {@link ChunkLexicalRepository#searchServingEligible}): identical to
     * {@link #search(float[], Document.Kind, UUID, int)} except the scope
     * EXISTS predicate additionally requires the owning paper to be
     * {@code VALIDATED} — a SUGGESTED, FLAGGED or REJECTED paper's chunks are
     * never returned, regardless of cosine similarity. The V33 subject branch
     * (knowledge-layer chunks with no exam-paper row) is gated the same way
     * the corpus law gates it: the chunk's own document must be
     * {@code VALIDATED} — the V29 {@code documents.validation_state} column
     * exists precisely so corpus imports are born SUGGESTED and nothing
     * serves without human validation.
     *
     * <p>Why this overload exists beside the neutral {@code search}: the
     * lexical side records boundary exclusion as enforced once, centrally,
     * never per-provider — until the fabric's central enforcer lands, the
     * serving path ({@code ContentRetrievalService}) calls THIS method and
     * the neutral {@code search} remains the benchmark/audit surface the
     * T-C13 harness replays against. Additive and reversible: nothing about
     * the existing search contract changes.</p>
     */
    public List<ChunkHit> searchServingEligible(float[] queryVector, Document.Kind kind,
                                                UUID curriculumVersionId, int limit) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException(
                    "curriculumVersionId is mandatory — chunk search never runs unscoped (T-C07)");
        }
        String literal = toVectorLiteral(queryVector);
        String kindFilter = kind == null ? "" : "and d.kind = '" + kind.name() + "'\n";
        String sql = """
                select c.id, c.document_row_id, d.document_id, d.kind, c.chunk_index,
                       c.content, c.page_start, c.page_end, c.element_ids,
                       c.embedding_model, 1 - (c.embedding <=> ?::vector) as score
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is not null
                  and c.embed_rev = ?
                  and (
                        exists (
                              select 1 from exam_papers p
                              join subjects s on s.id = p.subject_id
                              where s.curriculum_version_id = ?
                                and p.validation_state = 'VALIDATED'
                                and (p.question_paper_document_id = d.document_id
                                  or p.mark_scheme_document_id = d.document_id))
                     or exists (
                              select 1 from subjects s2
                              where s2.curriculum_version_id = ?
                                and s2.id = c.subject_id
                                and d.validation_state = 'VALIDATED'))
                """ + kindFilter + """
                order by c.embedding <=> ?::vector
                limit ?
                """;
        return jdbc.query(sql,
                (rs, i) -> mapHit(rs),
                literal, CURRENT_EMBED_REV, curriculumVersionId, curriculumVersionId,
                literal, limit);
    }

    private ChunkHit mapHit(java.sql.ResultSet rs) throws java.sql.SQLException {
        // element_ids is a JSONB array (house pattern, see MarkPoint.acceptanceCriteria)
        List<String> elementIds = new ArrayList<>();
        String raw = rs.getString("element_ids");
        if (raw != null && !raw.isBlank()) {
            try {
                elementIds = JSON.readValue(raw, new TypeReference<List<String>>() {
                });
            } catch (java.io.IOException e) {
                throw new java.sql.SQLException("element_ids is not a JSON array", e);
            }
        }
        return new ChunkHit(
                rs.getObject("id", UUID.class),
                rs.getObject("document_row_id", UUID.class),
                rs.getString("document_id"),
                rs.getString("kind"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                (Integer) rs.getObject("page_start"),
                (Integer) rs.getObject("page_end"),
                List.copyOf(elementIds),
                rs.getString("embedding_model"),
                rs.getDouble("score"));
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
