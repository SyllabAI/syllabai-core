package com.syllabai.bench;

import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T-C72 (rank-quality lane tranche 6): the per-kind arm seam in KIND-ARM form
 * — a dedicated EXTERNAL_NOTES fusion arm behind {@code BENCH_NOTES_KIND_ARM},
 * pre-registered in {@code ARM-COMPOSITION-PREREGISTRATION.md} (records
 * 8340542) BEFORE any run.
 *
 * <p><strong>Semantics (pinned by the pre-registration):</strong> per query,
 * the arm's candidate list = the top K EXTERNAL_NOTES chunks by arm A's
 * production candidate SQL — the same distance operator (pgvector cosine
 * {@code <=>}), embedding column, and embed_rev stamp
 * ({@link ChunkVectorRepository#CURRENT_EMBED_REV}) — over the same embedded
 * corpus, with the same query vector (the frozen preload served through the
 * production {@link EmbeddingProvider} port, stripped exactly as
 * {@code ContentRetrievalService} strips) — i.e. the production
 * serving-eligible scope + {@code AND kind = 'EXTERNAL_NOTES'} + {@code LIMIT K},
 * plus a deterministic {@code chunk_ref ASC} final tiebreak
 * ({@code document_id ASC, chunk_index ASC} — the portable
 * {@code document_checksum:ordinal} ref): the new arm cannot inject the
 * g2-024 ORDER-BY tie noise into any list. Under-fill happens ONLY at
 * kind-universe exhaustion (fewer in-scope EXTERNAL_NOTES chunks than K) and
 * is recorded honestly.</p>
 *
 * <p><strong>Fabric posture:</strong> the list enters BOTH fabrics (served
 * {@code allowAll} + compliant central-VALIDATED) as an ordinary THIRD arm
 * input via {@code Run005C.composeArms} — the shipped
 * {@code ReciprocalRankFusion} k=60, unweighted 3-arg ctor, unchanged; the
 * existing arms' lists byte-unchanged; the central boundary applies exactly
 * as shipped (pre-fusion). No query-kind awareness, no gold knowledge, no
 * per-query parameters: the same K for every query; the KIND is a corpus-side
 * chunk attribute (the T-C69 precedent). A cosine floor is NOT applied — the
 * pre-registration pins a distance-ordered top-K (the reach census basis:
 * NOTES-cosine ranks 4/6/8/8/11/13 of 350); MIN_COSINE is the rejected
 * CAP-form lever, not this arm's posture.</p>
 *
 * <p><strong>Absent-path identity:</strong> {@code BENCH_NOTES_KIND_ARM}
 * absent means this class is never CONSTRUCTED — the two-provider fabric list
 * is byte-identical by construction (the S8D/T-C65/T-C69 absent-path
 * precedent; {@code Run005C.composeArms} returns the recorded two-arm list
 * unchanged). No production file reads the gate: serving's own candidate
 * selection is untouched.</p>
 */
public final class NotesArmRetrieval implements RetrievalProvider {

    /** The only kind the arm ranks (the pre-registration's single lever). */
    public static final String ARM_KIND = "EXTERNAL_NOTES";

    /** Fabric-facing provider id (third entry in the manifest providers list). */
    static final String ARM_ID = "notes-kind-arm";

    /**
     * The kind-scoped candidate SQL — arm A's production candidate SQL
     * ({@code ChunkVectorRepository#searchServingEligible}: same select list,
     * same embed_rev stamp, same T-C07 + T-C20 VALIDATED scope EXISTS
     * predicates) + the kind predicate + the deterministic chunk_ref ASC
     * final tiebreak. Drift-guarded by Run005CKindArmTest (the
     * ChunkVectorRepositoryDiagnoseTest house pattern): if the production
     * serving SQL ever changes its predicate without this arm mirroring it,
     * the tests fail instead of the arm silently diverging.
     */
    static final String CANDIDATE_SQL = """
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
                            and p.validation_state = 'VALIDATED'
                            and (p.question_paper_document_id = d.document_id
                              or p.mark_scheme_document_id = d.document_id))
                 or exists (
                          select 1 from subjects s2
                          where s2.curriculum_version_id = ?
                            and s2.id = c.subject_id
                            and d.validation_state = 'VALIDATED'))
              and d.kind = 'EXTERNAL_NOTES'
            order by c.embedding <=> ?::vector, d.document_id asc, c.chunk_index asc
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embedding;
    private final int k;
    /** Query texts (stripped, the embed key) whose kind-arm list under-filled. */
    private final Set<String> underFilled = new LinkedHashSet<>();
    /**
     * Per-query memo of the arm's emitted list. The frozen vectors + frozen
     * corpus make the list a pure function of the stripped query text, and the
     * T-C77 rank-cap partition (NotesRankCapRetrieval) reads it POST-hoc after
     * the fabric has already consumed it — the memo keeps that read O(1) with
     * zero extra SQL (the pre-registered (e) latency claim's O(n)-pass-only
     * shape) and makes the determinism double-pass read the identical list
     * instances. Transparent for every recorded posture: same lists, same
     * order, same under-fill tracking; the absent path never constructs the
     * arm at all.
     */
    private final Map<String, List<RetrievalCandidate>> memo = new LinkedHashMap<>();

    /**
     * @param jdbc      the bench JdbcTemplate (the real Flyway-migrated,
     *                  snapshot-loaded Postgres)
     * @param embedding the SAME frozen query-vector provider arm A's
     *                  production path serves through (the production
     *                  {@code EmbeddingProvider} port — the same instance
     *                  {@code ArmA.productionRetriever} wires)
     * @param k         the pinned arm depth K (the {@code BENCH_NOTES_KIND_ARM}
     *                  value; must be positive — 0 is the absent path and never
     *                  constructs this class)
     */
    public NotesArmRetrieval(JdbcTemplate jdbc, EmbeddingProvider embedding, int k) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.embedding = Objects.requireNonNull(embedding, "embedding provider");
        if (k <= 0) {
            throw new IllegalArgumentException("kind-arm K must be > 0, got " + k
                    + " (0 is the absent path — never construct the arm)");
        }
        this.k = k;
    }

    /**
     * Spec parser: absent/blank = 0 (the recorded two-provider posture,
     * byte-identical — the arm is never constructed); a positive integer = the
     * pinned kind-arm depth K; anything else fails closed — never a
     * silently-different posture.
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

    @Override
    public String id() {
        return ARM_ID;
    }

    /**
     * {@code true}: unavailability is expressed by the honest empty list (the
     * arm never fabricates candidates) — the fabric-port convention arm A's
     * adapter mirrors.
     */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.normalizedQuery() == null || query.normalizedQuery().isBlank()) {
            return List.of(); // the production adapter's honest-empty contract
        }
        String stripped = query.normalizedQuery().strip();
        List<RetrievalCandidate> memoized = memo.get(stripped);
        if (memoized != null) {
            return memoized; // frozen inputs: the list is a pure function of the query text
        }
        float[] vector = embedding.embedQuery(stripped);
        if (vector == null || vector.length != embedding.dimension()) {
            throw new IllegalStateException("embedding provider " + embedding.model()
                    + " returned an inconsistent query vector (fail-closed)");
        }
        List<RetrievalCandidate> rows = jdbc.query(CANDIDATE_SQL, (rs, i) -> mapRow(rs),
                toVectorLiteral(vector), ChunkVectorRepository.CURRENT_EMBED_REV,
                query.scope().curriculumVersionId(), query.scope().curriculumVersionId(),
                toVectorLiteral(vector), k);
        if (rows.size() < k) {
            // kind-universe exhaustion — recorded honestly (idempotent across
            // the determinism double-pass: tracked by query text)
            underFilled.add(stripped);
        }
        List<RetrievalCandidate> immutable = List.copyOf(rows);
        memo.put(stripped, immutable);
        return immutable;
    }

    /** Queries (stripped embed keys) whose kind-arm list came up short of K. */
    int underFilledQueries() {
        return underFilled.size();
    }

    /**
     * Kind exclusivity, fail-closed: the SQL predicate admits only
     * EXTERNAL_NOTES rows — a different kind here means the SQL drifted from
     * the predicate the tests pin, and the arm refuses to emit the candidate.
     */
    static void requireNotesKind(String kind, String documentId) {
        if (!ARM_KIND.equals(kind)) {
            throw new IllegalStateException("kind-arm candidate of kind " + kind
                    + " on document " + documentId + " — the kind predicate drifted"
                    + " (fail-closed)");
        }
    }

    private static RetrievalCandidate mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String kind = rs.getString("kind");
        requireNotesKind(kind, rs.getString("document_id"));
        // element_ids is a JSONB array (house pattern, ChunkVectorRepository.mapHit)
        List<String> elementIds = new java.util.ArrayList<>();
        String raw = rs.getString("element_ids");
        if (raw != null && !raw.isBlank()) {
            try {
                elementIds = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                        });
            } catch (java.io.IOException e) {
                throw new java.sql.SQLException("element_ids is not a JSON array", e);
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        // RetrievalCandidate identity convention: chunk ordinals and page
        // ranges travel in metadata as provenance detail — the exact mapping
        // PgVectorRetrievalProvider performs for arm A, so fused candidates
        // reconstruct portable gold refs identically.
        metadata.put("chunk_index", String.valueOf(rs.getInt("chunk_index")));
        metadata.put("document_kind", kind);
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

    /** pgvector literal — mirrors ChunkVectorRepository.toVectorLiteral. */
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
