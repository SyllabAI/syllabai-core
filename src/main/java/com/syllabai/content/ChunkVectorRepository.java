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
 * single constant deciding which corpus generation serves. Stamps are written at
 * ingest time with this constant's then-value; the cut-over is THIS constant
 * flipping — rollback is flipping it back. One recorded exception to "rows are
 * never mutated in place": the 2026-09-28 paired cut-over re-stamped the
 * entire VALIDATED serving pool (965 chunks, kind census EQ 309 + QP 145 +
 * MS 161 + EN 350) 1→2 in the same window as the flip below, so the served set
 * is provably identical across the boundary (same model both revs,
 * gemini-embedding-001 @ 768-d — the rev stamp is a corpus-content-generation
 * marker, not a model marker). Evidence: syllabai records repo
 * {@code evidence/serving-rev2-restamp-cutover-2026-09-28/}.</p>
 */
@Repository
public class ChunkVectorRepository {

    /**
     * The corpus generation that serves (plan §6). FLIPPED 1→2 on 2026-09-28
     * as the PAIRED cut-over of standing-menu item (2) (operator-gated:
     * IM trace 1a0e8efc1773852d; eval-gated: the r7 serving-set generation,
     * traces 1a0e88bb060ed3b5 / 1a0e8a8180a3f8cd, which handed the menu item
     * its eval input — §8(d) 0.5618 full / 0.9167 micro, chunk-axis numbers
     * unchanged by a re-stamp, same model both revs). The flip is ADDITIVE
     * because the entire VALIDATED serving pool (965 chunks — exactly what the
     * gate served at rev=1) was re-stamped 1→2 in the same window; rev2
     * gate-eligible supply was 0 immediately before, so nothing that served at
     * rev=1 stops serving at rev=2 and no SUGGESTED content becomes servable
     * (the VALIDATED-only gate is untouched). The d523f57 revert condition
     * ("interim until rev2-era papers are teacher-validated") was REFUTED as
     * written — the teacher waves validated rev1-STAMPED content; corrected
     * standing menu: syllabai records repo
     * {@code evidence/serving-rev2-flipback-refutation-2026-09-28/REPORT.md};
     * execution evidence: {@code evidence/serving-rev2-restamp-cutover-2026-09-28/}.
     *
     * <p>History: 1 = V33 default (2,333 legacy glmocr chunks) → 2 on 09-20
     * (Task 32, offline eval gates G1–G3 on the embed-bridge-v2 substrate:
     * rev2 hit@10 9/9 vs rev1 0/9, 10/10 topical probes, 300/300
     * header+group-key complete) → back to 1 on 09-25 (d523f57, the designed
     * rollback posture, after the T-C23 0-hit anomaly: the serving-eligible
     * intersection went EMPTY because the only VALIDATED papers pointed at
     * rev1 documents while the entire rev2 corpus is born SUGGESTED;
     * evidence: {@code evidence/serving-0hit-anomaly-2026-09-25/REPORT.md})
     * → 2 on 09-28 (this flip, the paired re-stamp cut-over). Rollback =
     * flip this constant back to 1 AND re-stamp the 965-chunk VALIDATED pool
     * 2→1 (kind-scoped UPDATE recorded in the evidence pack) — the same pair
     * in reverse. Ingests after this point stamp embed_rev = 2. rev1
     * retirement (deletion) stays gated at R5.</p>
     */
    public static final int CURRENT_EMBED_REV = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    // ── T-C31 empty-path diagnostic predicates ─────────────────────────────
    // These MUST stay in lockstep with the WHERE clauses of search() /
    // searchServingEligible() below — ChunkVectorRepositoryDiagnoseTest asserts
    // (whitespace-normalized) that each serving SQL contains its matching
    // fragment, so the funnel can never silently drift from the gate it mirrors.

    /** The curriculum-scope EXISTS predicate (both branches) WITHOUT the
     *  validation gates — the T-C07 scope stage shared by the neutral search. */
    static final String SCOPE_EXISTS_NO_VALIDATION = """
            exists (
                  select 1 from exam_papers p
                  join subjects s on s.id = p.subject_id
                  where s.curriculum_version_id = ?
                    and (p.question_paper_document_id = d.document_id
                      or p.mark_scheme_document_id = d.document_id))
         or exists (
                  select 1 from subjects s2
                  where s2.curriculum_version_id = ?
                    and s2.id = c.subject_id)
            """;

    /** The curriculum-scope EXISTS predicate WITH the T-C20 VALIDATED-only
     *  gates on both branches — the exact serving-eligible scope stage. */
    static final String SCOPE_EXISTS_VALIDATED = """
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
                    and d.validation_state = 'VALIDATED')
            """;

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
        String kindFilter = kindFilter(kind);
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
        String kindFilter = kindFilter(kind);
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

    /**
     * T-C31 serving-emptiness observability: the stage funnel behind an EMPTY
     * search, in two round-trips. Call ONLY after
     * {@link #searchServingEligible(float[], Document.Kind, UUID, int)} has
     * returned empty — never on the happy path (the happy path must not grow
     * queries). The {@code servingEligible} count runs the EXACT
     * {@code searchServingEligible} WHERE predicate (same
     * {@link #SCOPE_EXISTS_VALIDATED} fragment, drift-guarded by test), so the
     * classification can never name a stage the serving gate does not actually
     * gate.
     *
     * <p>Read-only, zero writes, no serving semantics change: an empty result
     * stays empty — this only names WHY it is empty (T-C23: all three
     * empty-causes used to return the identical {@code 200 + []}).</p>
     */
    public SearchEmptyDiagnostics diagnoseEmpty(Document.Kind kind, UUID curriculumVersionId) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException(
                    "curriculumVersionId is mandatory — diagnostics never run unscoped (T-C07)");
        }
        String kindFilter = kindFilter(kind);
        String funnelSql = """
                select count(*) as chunks_in_scope,
                       count(*) filter (where c.embedding is not null) as embedded_in_scope,
                       count(*) filter (where c.embedding is not null
                                          and c.embed_rev = ?) as in_scope_at_rev
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where """ + SCOPE_EXISTS_NO_VALIDATION + kindFilter;
        SearchEmptyDiagnostics funnel = jdbc.queryForObject(funnelSql,
                (rs, i) -> new SearchEmptyDiagnostics(rs.getLong("chunks_in_scope"),
                        rs.getLong("embedded_in_scope"), rs.getLong("in_scope_at_rev"), 0),
                CURRENT_EMBED_REV, curriculumVersionId, curriculumVersionId);
        String eligibleSql = """
                select count(*)
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is not null
                  and c.embed_rev = ?
                  and """ + SCOPE_EXISTS_VALIDATED + kindFilter;
        Long eligible = jdbc.queryForObject(eligibleSql, Long.class,
                CURRENT_EMBED_REV, curriculumVersionId, curriculumVersionId);
        return new SearchEmptyDiagnostics(funnel.chunksInScope(), funnel.embeddedInScope(),
                funnel.inScopeAtRev(), eligible == null ? 0L : eligible);
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

    /**
     * Fixed SQL fragment per kind — an EXHAUSTIVE switch, not string
     * interpolation (R11): kind was never injectable (an enum), but the old
     * `"' + kind.name() + '"` concat sat one careless refactor away from an
     * injection seam. A switch over the enum makes the drift a compile error:
     * adding a Document.Kind constant breaks this method instead of silently
     * changing SQL text.
     */
    private static String kindFilter(Document.Kind kind) {
        if (kind == null) {
            return "";
        }
        return "and d.kind = '" + switch (kind) {
            case QUESTION_PAPER -> "QUESTION_PAPER";
            case MARK_SCHEME -> "MARK_SCHEME";
            case SYLLABUS -> "SYLLABUS";
            case OTHER -> "OTHER";
            case TEXTBOOK -> "TEXTBOOK";
            case EXTERNAL_NOTES -> "EXTERNAL_NOTES";
            case EXTERNAL_QUESTIONS -> "EXTERNAL_QUESTIONS";
        } + "'\n";
    }
}
