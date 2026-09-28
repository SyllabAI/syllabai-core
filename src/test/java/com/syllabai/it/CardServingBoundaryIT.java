package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.content.ChunkLexicalRepository;
import com.syllabai.content.ChunkHit;
import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.Document;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-C27 card-axis serving boundary (2026-09-28, operator directive): the 298
 * EXTERNAL_QUESTIONS cards are SUGGESTED and NON-SERVABLE — this IT pins that
 * contract against real Postgres with card-shaped fixtures, on BOTH serving
 * surfaces, while preserving the serving-validation gate.
 *
 * <ul>
 *   <li><b>Vector surface</b> ({@link ChunkVectorRepository#searchServingEligible}):
 *       the V33 subject branch (branch 2) serves a paper-less knowledge-layer
 *       chunk ONLY when the chunk's own document is {@code VALIDATED} — a
 *       SUGGESTED card therefore never serves, however close the cosine match.
 *       This gate had SQL-text coverage only (T-C20); it now has row-level
 *       proof.</li>
 *   <li><b>Lexical surface</b> ({@link ChunkLexicalRepository#searchServingEligible}):
 *       paper-anchored only (the V33 subject branch is deliberately absent
 *       there), so a card can never surface on it at any validation state.</li>
 *   <li><b>Boundary invariant</b>: zero non-VALIDATED hits across both serving
 *       surfaces — the arm-B VALIDATION_BOUNDARY_VIOLATION hard-fail, encoded
 *       as a permanent fixture test.</li>
 *   <li><b>Dual-view design pinned</b>: the NEUTRAL search surfaces (the
 *       benchmark/audit surfaces the T-C13 harness replays against for the
 *       ALL-denominator views) DO return SUGGESTED rows by design — this IT
 *       guards them against a future over-tightening "fix".</li>
 *   <li><b>The flip contract</b>: flipping the card document's own
 *       validation_state to VALIDATED (inside this throwaway container ONLY —
 *       never production, never agent-asserted) makes the card servable via
 *       branch 2, proving the exclusion is state-driven, not kind-driven.
 *       This is exactly the mechanism the operator's card validation wave
 *       relies on when it lands; the gate itself is untouched by this test.</li>
 * </ul>
 *
 * Fixture shape mirrors production truth (T-C27 apply, records a02a7da): cards
 * are EXTERNAL_QUESTIONS documents whose chunks carry {@code subject_id} and
 * NO exam-paper row; QP/MS chunks anchor through exam_papers. Docker-gated —
 * runs on the CI lane, never in the agent sandbox.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(OrderAnnotation.class)
class CardServingBoundaryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkVectorRepository vectors;
    @Autowired
    private ChunkLexicalRepository lexical;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;

    private static final String QUERY = "electrolysis of molten lead bromide at the cathode";

    /** Scope + subject ids, assigned by the seed and reused by the ordered tests. */
    private static volatile UUID scopeId;
    private static volatile UUID subjectId;

    /** Deterministic doc/chunk row ids (no cross-test field state). */
    private static UUID rowId(String seed) {
        return UUID.nameUUIDFromBytes(("it-card-boundary|" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private static final UUID DOC_QP_A = rowId("doc-qp-a");
    private static final UUID DOC_MS_A = rowId("doc-ms-a");
    private static final UUID DOC_QP_B = rowId("doc-qp-b");
    private static final UUID DOC_MS_B = rowId("doc-ms-b");
    private static final UUID DOC_CARD_1 = rowId("doc-card-1");
    private static final UUID DOC_CARD_2 = rowId("doc-card-2");

    private static final String CONTENT_QP_A = "Electrolysis of molten lead bromide: lead forms at the cathode.";
    private static final String CONTENT_MS_A = "Mark scheme: cathode product is lead in molten lead bromide electrolysis.";
    private static final String CONTENT_QP_B = "Electrolysis of molten lead bromide explains why bromine forms at the anode.";
    private static final String CONTENT_MS_B = "Mark scheme: anode product is bromine in molten lead bromide electrolysis.";
    private static final String CONTENT_CARD_1 = "Card: in electrolysis of molten lead bromide the cathode attracts lead ions.";
    private static final String CONTENT_CARD_2 = "Card: molten lead bromide electrolysis cathode equation Pb2+ + 2e- -> Pb.";

    /** Deterministic 768-dim query/document vector (no external embedding calls). */
    private static float[] queryVector(String salt) {
        float[] v = new float[768];
        int h = salt.hashCode();
        for (int i = 0; i < 768; i++) {
            v[i] = ((h * (i + 7)) % 997) / 997.0f - 0.5f;
        }
        return v;
    }

    private void insertDocument(UUID rowId, String documentId, Document.Kind kind, String state,
                                String content) {
        jdbc.update("""
                insert into documents (id, document_id, schema_version, doc_version, kind, source_uri,
                    mime_type, checksum, page_count, element_count, text_element_count,
                    source_engine, source_engine_version, canonical_json, validation_state, created_at)
                values (?, ?, '1.0', 1, ?, 'it://card-boundary', 'application/pdf', ?, 1, 1, 1,
                    'it', '1', ?::jsonb, ?, now())
                """, rowId, documentId, kind.name(), documentId, "{}", state);
        UUID chunkId = rowId("chunk|" + documentId);
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, page_start,
                    page_end, element_ids, token_estimate, subject_id, created_at)
                values (?, ?, 0, ?, 1, 1, '[]'::jsonb, 24, ?, now())
                """, chunkId, rowId, content, subjectId);
        vectors.storeEmbedding(chunkId, queryVector(content), "it-card-boundary");
    }

    private List<ChunkHit> vectorServing() {
        return vectors.searchServingEligible(queryVector(QUERY), null, scopeId, 20);
    }

    private List<ChunkHit> lexicalServing() {
        return lexical.searchServingEligible(QUERY, Set.of(), scopeId, 20);
    }

    private List<ChunkHit> vectorNeutral() {
        return vectors.search(queryVector(QUERY), null, scopeId, 20);
    }

    private List<ChunkHit> lexicalNeutral() {
        return lexical.search(QUERY, Set.of(), scopeId, 20);
    }

    private static Set<UUID> chunkIds(List<ChunkHit> hits) {
        return hits.stream().map(ChunkHit::chunkId).collect(Collectors.toSet());
    }

    @Test
    @Order(1)
    @DisplayName("seed: VALIDATED paper A + SUGGESTED paper B (QP/MS) + two SUGGESTED cards (no paper rows)")
    void seed() {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "4CH1-BND", "IT fixture card boundary",
                CurriculumVersion.Status.ACTIVE));
        scopeId = cv.id();
        Subject subject = subjects.save(new Subject(cv, "4CH1-BND", "Chemistry (card boundary)"));
        subjectId = subject.id();

        // paper A: VALIDATED — the compliant control corpus
        ExamPaper paperA = examPapers.save(new ExamPaper(subjectId, "IT validated paper",
                "Edexcel", "IGCSE", null, null, "4CH1-BND/1C",
                DOC_QP_A.toString(), DOC_MS_A.toString(),
                ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        paperA.validate();
        examPapers.save(paperA);
        // paper B: born SUGGESTED (entity default) — the boundary exclusion control
        examPapers.save(new ExamPaper(subjectId, "IT suggested paper",
                "Edexcel", "IGCSE", null, null, "4CH1-BND/2C",
                DOC_QP_B.toString(), DOC_MS_B.toString(),
                ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));

        insertDocument(DOC_QP_A, "it-card-bnd-qp-a", Document.Kind.QUESTION_PAPER, "SUGGESTED", CONTENT_QP_A);
        insertDocument(DOC_MS_A, "it-card-bnd-ms-a", Document.Kind.MARK_SCHEME, "SUGGESTED", CONTENT_MS_A);
        insertDocument(DOC_QP_B, "it-card-bnd-qp-b", Document.Kind.QUESTION_PAPER, "SUGGESTED", CONTENT_QP_B);
        insertDocument(DOC_MS_B, "it-card-bnd-ms-b", Document.Kind.MARK_SCHEME, "SUGGESTED", CONTENT_MS_B);
        // the T-C27 card shape: EXTERNAL_QUESTIONS, SUGGESTED, subject-linked, NO exam-paper row
        insertDocument(DOC_CARD_1, "it-card-bnd-card-1", Document.Kind.EXTERNAL_QUESTIONS, "SUGGESTED", CONTENT_CARD_1);
        insertDocument(DOC_CARD_2, "it-card-bnd-card-2", Document.Kind.EXTERNAL_QUESTIONS, "SUGGESTED", CONTENT_CARD_2);

        // seed sanity: the neutral surfaces see ALL six chunks — the data matches;
        // only the serving gate decides what serves
        assertThat(vectorNeutral()).hasSize(6);
        assertThat(lexicalNeutral()).hasSize(6);
    }

    @Test
    @Order(2)
    @DisplayName("the VALIDATED paper's chunks serve through both surfaces (control group)")
    void validatedPaperServesThroughBothSurfaces() {
        Set<UUID> expected = Set.of(rowId("chunk|it-card-bnd-qp-a"), rowId("chunk|it-card-bnd-ms-a"));
        assertThat(chunkIds(vectorServing())).containsAll(expected);
        assertThat(chunkIds(lexicalServing())).containsAll(expected);
    }

    @Test
    @Order(3)
    @DisplayName("SUGGESTED paper chunks never serve on either surface, however strong the match")
    void suggestedPaperChunksNeverServed() {
        Set<UUID> forbidden = Set.of(rowId("chunk|it-card-bnd-qp-b"), rowId("chunk|it-card-bnd-ms-b"));
        assertThat(chunkIds(vectorServing())).doesNotContainAnyElementsOf(forbidden);
        assertThat(chunkIds(lexicalServing())).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    @Order(4)
    @DisplayName("SUGGESTED cards are non-servable: absent from vector branch 2 AND lexical, all-kind and kind-narrowed")
    void suggestedCardsNeverServed() {
        Set<UUID> cardChunks = Set.of(rowId("chunk|it-card-bnd-card-1"), rowId("chunk|it-card-bnd-card-2"));
        assertThat(chunkIds(vectorServing())).doesNotContainAnyElementsOf(cardChunks);
        assertThat(chunkIds(lexicalServing())).doesNotContainAnyElementsOf(cardChunks);

        // an EXTERNAL_QUESTIONS-narrowed serving query is empty too (no paper anchoring exists)
        assertThat(vectors.searchServingEligible(queryVector(QUERY),
                Document.Kind.EXTERNAL_QUESTIONS, scopeId, 20)).isEmpty();
        assertThat(lexical.searchServingEligible(QUERY,
                Set.of(Document.Kind.EXTERNAL_QUESTIONS), scopeId, 20)).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("boundary invariant: zero non-VALIDATED hits across both serving surfaces (arm-B hard fail, encoded)")
    void servingSurfacesHaveZeroBoundaryViolations() {
        for (List<ChunkHit> hits : List.of(vectorServing(), lexicalServing())) {
            for (ChunkHit hit : hits) {
                Integer violations = jdbc.queryForObject("""
                        select count(*) from documents d
                        where d.document_id = ?
                          and d.validation_state <> 'VALIDATED'
                          and not exists (
                                select 1 from exam_papers p
                                where p.validation_state = 'VALIDATED'
                                  and (p.question_paper_document_id = d.document_id
                                    or p.mark_scheme_document_id = d.document_id))
                        """, Integer.class, hit.documentId());
                assertThat(violations).as("boundary violation on " + hit.documentId()).isZero();
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("the neutral benchmark surfaces still return SUGGESTED rows — the ALL-denominator view is by design")
    void neutralBenchmarkSearchStillSurfacesSuggestedRows() {
        Set<UUID> suggested = Set.of(
                rowId("chunk|it-card-bnd-qp-b"), rowId("chunk|it-card-bnd-ms-b"),
                rowId("chunk|it-card-bnd-card-1"), rowId("chunk|it-card-bnd-card-2"));
        assertThat(chunkIds(vectorNeutral())).containsAll(suggested);
        assertThat(chunkIds(lexicalNeutral())).containsAll(suggested);
    }

    @Test
    @Order(7)
    @DisplayName("flip contract (test container ONLY): card serves when ITS OWN document turns VALIDATED; gates again on revert")
    void cardServesOnlyWhenItsOwnDocumentIsValidated() {
        UUID cardChunk = rowId("chunk|it-card-bnd-card-1");
        assertThat(chunkIds(vectorServing())).doesNotContain(cardChunk);

        // flip INSIDE THE THROWAWAY CONTAINER — this is the gate mechanism proof,
        // never a production action (no agent-asserted validation, ever)
        jdbc.update("update documents set validation_state = 'VALIDATED' where id = ?", DOC_CARD_1);
        assertThat(chunkIds(vectorServing())).contains(cardChunk);

        // revert: the fixture world returns to production truth (cards SUGGESTED)
        jdbc.update("update documents set validation_state = 'SUGGESTED' where id = ?", DOC_CARD_1);
        assertThat(chunkIds(vectorServing())).doesNotContain(cardChunk);

        // lexical stays paper-anchored-only at every state (V33: no subject branch there)
        assertThat(chunkIds(lexicalServing())).doesNotContain(cardChunk);
    }
}
