package com.syllabai.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Owns the lexical (BM25-style) search surface of {@code document_chunks}
 * (T-C14): Postgres full-text over the {@code content_tsv} tsvector column
 * (flyway V28, generated from {@code content}) with the GIN index
 * {@code idx_document_chunks_content_tsv}. JdbcTemplate-meets-native-SQL, the
 * same posture {@link ChunkVectorRepository} uses for pgvector — JPA cannot
 * express {@code ts_rank_cd}/{@code websearch_to_tsquery}.
 *
 * <p>Scoring: {@code ts_rank_cd} (cover density) is the native lexical score —
 * a logging/benchmark input only; rank-only fusion never reads scores.
 * Query parsing: {@code websearch_to_tsquery} — tolerant of learner phrasing
 * (quoted phrases, OR, minus), never a syntax-error surface. Deterministic
 * ordering: score descending, then chunk id ascending (stable tiebreak so the
 * same database state always yields the same ranking — T-C13 reproducibility).</p>
 *
 * <p>T-C07 (mandatory curriculum scoping): {@code curriculumVersionId} is
 * required — an EXISTS predicate narrows candidacy to chunks whose document is
 * a question paper or mark scheme of an exam paper whose subject belongs to
 * this curriculum version (the DB-verified join path
 * {@code document_chunks → documents → exam_papers (QP/MS document_id) →
 * subjects → curriculum_versions}). Unlinked documents resolve to no
 * curriculum and are never served — fail-closed, never unscoped. The
 * curriculum id is a bound parameter (never SQL text); document kinds are
 * folded into the SQL text as code-controlled enum names (house pattern, see
 * {@link ChunkVectorRepository}).</p>
 */
@Repository
public class ChunkLexicalRepository {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;

    public ChunkLexicalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Lexical (BM25-style) search over the tsvector column. {@code kinds} empty
     * searches all document kinds. Blank queries fail closed to an empty list
     * before any SQL runs (a blank tsquery matches nothing — running it would
     * be noise).
     *
     * @param normalizedQuery     the query text (non-blank)
     * @param kinds               document-kind narrowing (empty = all kinds)
     * @param curriculumVersionId the mandatory curriculum scope id (T-C07)
     * @param limit               candidate bound (pre-fusion)
     * @return hits best-first by ts_rank_cd (may be empty)
     */
    public List<ChunkHit> search(String normalizedQuery, Set<Document.Kind> kinds,
                                 UUID curriculumVersionId, int limit) {
        if (curriculumVersionId == null) {
            throw new IllegalArgumentException(
                    "curriculumVersionId is mandatory — chunk search never runs unscoped (T-C07)");
        }
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        String kindFilter = kindFilter(kinds);
        String sql = """
                select c.id, c.document_row_id, d.document_id, d.kind, c.chunk_index,
                       c.content, c.page_start, c.page_end, c.element_ids,
                       null as embedding_model, ts_rank_cd(c.content_tsv, q.tsq) as score
                from document_chunks c
                join documents d on d.id = c.document_row_id
                cross join (select websearch_to_tsquery('english', ?) as tsq) q
                where c.content_tsv @@ q.tsq
                  and exists (
                        select 1 from exam_papers p
                        join subjects s on s.id = p.subject_id
                        where s.curriculum_version_id = ?
                          and (p.question_paper_document_id = d.document_id
                            or p.mark_scheme_document_id = d.document_id))
                """ + kindFilter + """
                order by ts_rank_cd(c.content_tsv, q.tsq) desc, c.id
                limit ?
                """;
        return jdbc.query(sql,
                (rs, i) -> mapHit(rs),
                normalizedQuery, curriculumVersionId, limit);
    }

    /** Code-controlled enum names folded into SQL text (never user input). */
    private static String kindFilter(Set<Document.Kind> kinds) {
        if (kinds == null || kinds.isEmpty()) {
            return "";
        }
        String names = kinds.stream()
                .map(k -> "'" + k.name() + "'")
                .sorted()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return "  and d.kind in (" + names + ")\n";
    }

    private ChunkHit mapHit(java.sql.ResultSet rs) throws java.sql.SQLException {
        // element_ids is a JSONB array (house pattern, see ChunkVectorRepository)
        List<String> elementIds = new ArrayList<>();
        String raw = rs.getString("element_ids");
        if (raw != null && !raw.isBlank()) {
            try {
                elementIds = JSON.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
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
}
