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
 */
@Repository
public class ChunkVectorRepository {

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
     */
    public List<ChunkHit> search(float[] queryVector, Document.Kind kind, int limit) {
        String literal = toVectorLiteral(queryVector);
        String kindFilter = kind == null ? "" : "and d.kind = '" + kind.name() + "'\n";
        String sql = """
                select c.id, c.document_row_id, d.document_id, d.kind, c.chunk_index,
                       c.content, c.page_start, c.page_end, c.element_ids,
                       c.embedding_model, 1 - (c.embedding <=> ?::vector) as score
                from document_chunks c
                join documents d on d.id = c.document_row_id
                where c.embedding is not null
                """ + kindFilter + """
                order by c.embedding <=> ?::vector
                limit ?
                """;
        return jdbc.query(sql,
                (rs, i) -> mapHit(rs),
                literal, literal, limit);
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
