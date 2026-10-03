package com.syllabai.bench;

import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-C72 (rank-quality lane tranche 6): the arm-composition seam in KIND-ARM
 * form — a dedicated EXTERNAL_NOTES fusion arm behind
 * {@code BENCH_NOTES_KIND_ARM}, pre-registered in
 * {@code ARM-COMPOSITION-PREREGISTRATION.md} (records 8340542) BEFORE any
 * run.
 *
 * <p><strong>Semantics (pinned by the pre-registration):</strong> per query,
 * the kind-arm's candidate list = the top K EXTERNAL_NOTES chunks by the SAME
 * distance operator ({@code <=>}), embedding column ({@code c.embedding}) and
 * embed_rev stamp ({@code ChunkVectorRepository.CURRENT_EMBED_REV}) as arm
 * A's production vector SQL, over the same embedded corpus, with the SAME
 * query vector (the frozen preload served through the production
 * {@link EmbeddingProvider} port) — the production candidate shape +
 * {@code AND d.kind = 'EXTERNAL_NOTES'} + {@code LIMIT K}, plus a
 * deterministic {@code document_id ASC, chunk_index ASC} final tiebreak: the
 * new arm cannot inject the g2-024 ORDER-BY tie noise into any list. The
 * list enters BOTH fabrics as an ordinary third arm input; the shipped
 * {@code ReciprocalRankFusion} k=60 (unweighted 3-arg ctor) is unchanged,
 * the existing arms' lists are byte-unchanged, and the central boundary
 * applies exactly as shipped (pre-fusion) — the compliant view's central
 * VALIDATED gate excludes the kind-arm's SUGGESTED-paper refs exactly as
 * recorded.</p>
 *
 * <p><strong>The ranking population IS the lever:</strong> notes compete only
 * against notes (the reach census: the f-lost flips' fresh covering carriers
 * — ALL EXTERNAL_NOTES — sit at notes-cosine ranks 4&ndash;13 of 350 while
 * their GLOBAL ranks are 56&ndash;219 of 4510; buried ONLY in the arms'
 * cross-kind ordering, which is why the arm-rank-ordered floor starved). The
 * scope predicate keeps the T-C07 in-scope anchoring (paper-anchored or
 * subject-anchored within the curriculum version); the paper VALIDATION-STATE
 * gate is deliberately NOT part of this arm — the census universe is the
 * embedded notes corpus itself, and boundary enforcement happens ONCE,
 * centrally, pre-fusion (the retrieval contract's invariant 1), never
 * per-provider.</p>
 *
 * <p><strong>Absent-path identity:</strong> a K &le; 0 creates NOTHING — the
 * fabric's arm list stays the recorded two-provider list, byte-identical by
 * construction (the S8D absent-path precedent). Under-fill at universe
 * exhaustion (the SQL returns fewer than K rows) is returned as-is and
 * recorded honestly. Determinism: the arm's SQL is fully ordered (distance
 * ASC, then the tiebreak); the run's double retrieval pass covers the
 * kind-arm path end-to-end. No query-kind awareness, no gold knowledge, no
 * per-query parameters: the same K for every query — the KIND is a
 * corpus-side chunk attribute (the T-C69 precedent).</p>
 */
public final class NotesArmRetrieval {

    /** The only kind the arm ranks (the pre-registration's single lever). */
    public static final String ARM_KIND = "EXTERNAL_NOTES";

    /** Registry-facing id of the third fusion input (vector arm, kind-scoped). */
    public static final String ARM_ID = "pgvector:EXTERNAL_NOTES";

    private static final ObjectMapper JSON = new ObjectMapper();

    private NotesArmRetrieval() {
    }

    /**
     * Spec parser: absent/blank = 0 (the recorded posture, byte-identical); a
     * positive integer = the kind-arm's K; anything else fails closed — never
     * a silently-different posture.
     */
    static int kindArmForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        String trimmed = spec.trim();
        int value;
        try {
            value = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unknown BENCH_NOTES_KIND_ARM spec: '" + spec
                    + "' (expected a positive integer, e.g. 15) — fail-closed");
        }
        if (value < 0 || value > 10_000) {
            throw new IllegalStateException("BENCH_NOTES_KIND_ARM out of range: " + value
                    + " (expected 0..10000) — fail-closed");
        }
        return value;
    }

    /**
     * A K &le; 0 returns NULL (absent-path byte-identity by construction —
     * the fabric's arm list stays the recorded two-provider list).
     */
    public static RetrievalProvider maybeCreate(JdbcTemplate jdbc, EmbeddingProvider frozen,
                                                int kindArm) {
        if (kindArm <= 0) {
            return null;
        }
        return new KindArmProvider(Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(frozen, "frozen embedding provider"), kindArm);
    }

    /** Portable evidence identity — the same shape {@code Run005C.refs()} records. */
    static String refOf(RetrievalCandidate candidate) {
        String ordinal = candidate.metadata().getOrDefault("chunk_index", "-1");
        return candidate.documentId() + ":" + ordinal;
    }

    private static final class KindArmProvider implements RetrievalProvider {

        private final JdbcTemplate jdbc;
        private final EmbeddingProvider frozen;
        private final int k;

        KindArmProvider(JdbcTemplate jdbc, EmbeddingProvider frozen, int k) {
            this.jdbc = jdbc;
            this.frozen = frozen;
            if (k <= 0) {
                throw new IllegalArgumentException("kind-arm K must be > 0, got " + k);
            }
            this.k = k;
        }

        @Override
        public String id() {
            return ARM_ID;
        }

        /** Always true: unavailability is the delegate's honest empty/failure, not a health flag. */
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            CurriculumScope scope = query.scope();
            if (scope == null) {
                throw new IllegalArgumentException(
                        "curriculum scope is mandatory — the kind-arm never runs unscoped (T-C07)");
            }
            if (query.normalizedQuery() == null || query.normalizedQuery().isBlank()) {
                return List.of();
            }
            // The SAME query vector arm A serves: the frozen preload through the
            // production EmbeddingProvider port, embedded from the normalized text
            // exactly where ContentRetrievalService.search strips it. A query text
            // missing from the frozen preload fails hard here (the
            // MissingFrozenVectorException shape) — never a silent empty.
            float[] queryVector = frozen.embedQuery(query.normalizedQuery().strip());
            if (queryVector == null || queryVector.length != frozen.dimension()) {
                throw new IllegalStateException("embedding provider " + frozen.model()
                        + " returned an inconsistent query vector");
            }
            String literal = toVectorLiteral(queryVector);
            return jdbc.query(KindArmSql.SQL, NotesArmRetrieval::mapHit,
                    literal, ChunkVectorRepository.CURRENT_EMBED_REV,
                    scope.curriculumVersionId(), scope.curriculumVersionId(),
                    literal, k);
        }
    }

    /**
     * The kind-arm SQL — arm A's production vector candidate shape
     * ({@code ChunkVectorRepository.searchServingEligible}: the same select
     * list, the same {@code <=>} operator over {@code c.embedding}, the same
     * embed_rev stamp, the T-C07 scope EXISTS predicate over the curriculum
     * version) plus the kind predicate, the deterministic final tiebreak and
     * LIMIT K. The paper VALIDATION-STATE gate is deliberately absent here:
     * the ranking population is the embedded notes corpus (the census
     * universe); the boundary is enforced once, centrally, pre-fusion — never
     * per-provider. THE KIND IS NOT INJECTABLE: a fixed SQL fragment (the R11
     * discipline — the production {@code kindFilter} switch exists because
     * the kind is a variable there; this arm has exactly one kind, baked
     * into the text).
     */
    private static final class KindArmSql {
        private static final String SQL = """
                select c.id, c.document_row_id, d.doc_version, d.document_id, d.kind, c.chunk_index,
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
                  and d.kind = 'EXTERNAL_NOTES'
                order by c.embedding <=> ?::vector, d.document_id asc, c.chunk_index asc
                limit ?
                """;
    }

    /** Mirrors the production row mapping ({@code ChunkVectorRepository.mapHit}) lifted
     * straight into the {@code RetrievalCandidate} convention (the identity
     * {@code PgVectorRetrievalProvider.toCandidate} uses — the chunk UUID is the
     * {@code evidenceLocator} rank-only fusion deduplicates on; the ordinal travels
     * in metadata so the portable gold ref reconstructs). */
    private static RetrievalCandidate mapHit(ResultSet rs, int i) throws SQLException {
        List<String> elementIds = new ArrayList<>();
        String raw = rs.getString("element_ids");
        if (raw != null && !raw.isBlank()) {
            try {
                elementIds = JSON.readValue(raw, new TypeReference<List<String>>() {
                });
            } catch (java.io.IOException e) {
                throw new SQLException("element_ids is not a JSON array", e);
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("chunk_index", String.valueOf(rs.getInt("chunk_index")));
        metadata.put("document_kind", rs.getString("kind"));
        Integer pageStart = (Integer) rs.getObject("page_start");
        Integer pageEnd = (Integer) rs.getObject("page_end");
        if (pageStart != null) {
            metadata.put("page_start", String.valueOf(pageStart));
        }
        if (pageEnd != null) {
            metadata.put("page_end", String.valueOf(pageEnd));
        }
        if (!elementIds.isEmpty()) {
            metadata.put("element_ids", String.join(",", elementIds));
        }
        return new RetrievalCandidate(
                ARM_ID,
                rs.getObject("document_row_id", UUID.class),
                rs.getString("document_id"),
                rs.getInt("doc_version"),
                String.valueOf(rs.getObject("id", UUID.class)),
                null,
                null,
                rs.getString("content"),
                rs.getDouble("score"),
                null,
                rs.getString("embedding_model"),
                Map.copyOf(metadata));
    }

    /** The production literal format ({@code ChunkVectorRepository.toVectorLiteral}) —
     * the same float rendering means the same query the DB sees. */
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
