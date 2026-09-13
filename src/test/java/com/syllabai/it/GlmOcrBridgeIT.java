package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionController;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionSpec;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecord;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper.ReviewFinding;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService;
import com.syllabai.teacher.ingestion.GlmOcrMarkSchemeDraftDto;
import com.syllabai.teacher.ingestion.GlmOcrPaperDraftDto;
import com.syllabai.teacher.ingestion.GlmOcrReconciliationDto;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-C02 integration test: the GLM-OCR bridge end-to-end against a real pgvector
 * Postgres, driven by the six REAL GLM-markdown-sample fixtures (three QP/MS
 * pairs, produced by the verified parser @ 9eb35ab). Verifies:
 * canonical persistence with deterministic identities and checksum idempotency,
 * the assessment bridge through the existing T-011 path (all SUGGESTED), rerun
 * safety, the two audited conflicts staying review-visible, learner-serving
 * exclusion of unvalidated content, and that embedding is never implicit.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlmOcrBridgeIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** Deterministic offline double (same as ContentPipelineIT) — proves the bridge
     *  never calls it: all chunks stay pending after bridge ingestion. */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        EmbeddingProvider fakeEmbeddingProvider() {
            return new ContentPipelineIT.HashingEmbeddingProvider();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr");

    @Autowired
    private GlmOcrIngestionService bridge;
    @Autowired
    private PastPaperIngestionService pastPaperIngestion;
    @Autowired
    private GlmOcrBridgeRecordRepository bridgeRecords;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private MarkPointRepository markPoints;
    @Autowired
    private QuestionController learnerQuestions;
    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    private GlmOcrIngestionService.GlmOcrPairRequest pair(String name) throws Exception {
        String qpJson = Files.readString(FIXTURES.resolve(name + "/qp-canonical.json"));
        String msJson = Files.readString(FIXTURES.resolve(name + "/ms-canonical.json"));
        return new GlmOcrIngestionService.GlmOcrPairRequest(
                JSON.readValue(qpJson, CanonicalDocumentDto.class), qpJson,
                JSON.readValue(msJson, CanonicalDocumentDto.class), msJson,
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/qp-draft.json")),
                        GlmOcrPaperDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/ms-draft.json")),
                        GlmOcrMarkSchemeDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/reconciliation.json")),
                        GlmOcrReconciliationDto.class));
    }

    private static final class Counts {
        final long documents;
        final long chunks;
        final long bridgeRecords;
        final long versions;
        final long schemes;
        final long papers;

        Counts(DocumentRepository documents, DocumentChunkRepository chunks,
               GlmOcrBridgeRecordRepository bridgeRecords, QuestionVersionRepository versions,
               MarkSchemeRepository schemes, ExamPaperRepository papers) {
            this.documents = documents.count();
            this.chunks = chunks.count();
            this.bridgeRecords = bridgeRecords.count();
            this.versions = versions.count();
            this.schemes = schemes.count();
            this.papers = papers.count();
        }
    }

    private Counts counts() {
        return new Counts(documents, chunks, bridgeRecords, questionVersions, markSchemes,
                examPapers);
    }

    // ── 1. canonical persistence (T-013 path, deterministic identity) ────────────

    @Test
    @Order(1)
    @DisplayName("June pair: canonical QP/MS persist with parser identities, chunks keep element provenance, nothing embedded")
    void juneCanonicalPersistence() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest fixtures = pair("june-2025-wph11-01");
        GlmOcrIngestionService.PairResult result = bridge.ingestPair(fixtures, null);

        // deterministic parser identity survives the boundary (never re-generated)
        assertThat(result.qpDocument().documentId())
                .isEqualTo(fixtures.qpCanonical().documentId());
        assertThat(result.msDocument().documentId())
                .isEqualTo(fixtures.msCanonical().documentId());
        assertThat(result.qpDocument().duplicate()).isFalse();
        assertThat(result.msDocument().duplicate()).isFalse();

        // checksum-idempotent rows exist with the exact parser identities
        Document qpDoc = documents.findByChecksum(fixtures.qpCanonical().source().checksum())
                .orElseThrow();
        Document msDoc = documents.findByChecksum(fixtures.msCanonical().source().checksum())
                .orElseThrow();
        assertThat(qpDoc.documentId()).isEqualTo(fixtures.qpCanonical().documentId());
        assertThat(qpDoc.kind()).isEqualTo(Document.Kind.QUESTION_PAPER);
        assertThat(msDoc.kind()).isEqualTo(Document.Kind.MARK_SCHEME);
        assertThat(qpDoc.sourceEngine()).isEqualTo("glm-ocr-markdown");
        assertThat(qpDoc.sourceEngineVersion()).isEqualTo("1.0.0");

        // canonical JSON survives the JSONB persistence boundary (content-preserving)
        assertThat(JSON.readTree(qpDoc.canonicalJson()))
                .isEqualTo(JSON.readTree(fixtures.qpCanonicalJson()));
        assertThat(JSON.readTree(msDoc.canonicalJson()))
                .isEqualTo(JSON.readTree(fixtures.msCanonicalJson()));

        // chunks exist and their elementIds are exactly the canonical text elements
        assertChunkProvenance(qpDoc.id(), fixtures.qpCanonical());
        assertChunkProvenance(msDoc.id(), fixtures.msCanonical());

        // embedding intentionally skipped: every chunk is still pending
        assertThat(chunks.findPendingByDocumentRowId(qpDoc.id()))
                .hasSameSizeAs(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpDoc.id()));
        assertThat(chunks.findPendingByDocumentRowId(msDoc.id()))
                .hasSameSizeAs(chunks.findByDocumentRowIdOrderByChunkIndexAsc(msDoc.id()));
        assertThat(result.embeddingSkipped()).isTrue();
    }

    private void assertChunkProvenance(UUID documentRowId, CanonicalDocumentDto canonical) {
        List<DocumentChunk> stored = chunks.findByDocumentRowIdOrderByChunkIndexAsc(documentRowId);
        assertThat(stored).isNotEmpty();

        // expected coverage per the documented §9 rule: textBlocks + tables with text,
        // equations with text or latex — figures never enter the retrieval index
        Set<String> chunkable = new HashSet<>();
        canonical.textBlocks().stream()
                .filter(e -> e != null && e.text() != null && !e.text().isBlank())
                .forEach(e -> chunkable.add(e.elementId()));
        canonical.tables().stream()
                .filter(e -> e != null && e.text() != null && !e.text().isBlank())
                .forEach(e -> chunkable.add(e.elementId()));
        canonical.equations().stream()
                .filter(e -> e != null && ((e.text() != null && !e.text().isBlank())
                        || (e.latex() != null && !e.latex().isBlank())))
                .forEach(e -> chunkable.add(e.elementId()));
        assertThat(chunkable).isNotEmpty();

        Set<String> chunkElements = stored.stream()
                .flatMap(c -> c.elementIds().stream())
                .collect(Collectors.toSet());
        // provenance preserved: chunk element ids are exactly the chunkable elements
        assertThat(chunkElements).isEqualTo(chunkable);
        // no element duplicated across chunks
        assertThat(stored.stream().flatMap(c -> c.elementIds().stream())
                .collect(Collectors.groupingBy(e -> e, Collectors.counting()))
                .values().stream().filter(c -> c > 1)).isEmpty();
    }

    // ── 2. assessment bridge (T-011 path, all SUGGESTED) ────────────────────────

    @Test
    @DisplayName("June pair: paper + 20 questions + versions + parts + 20 schemes + 51 points, all SUGGESTED, linked correctly")
    void juneAssessmentBridge() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest fixtures = pair("june-2025-wph11-01");
        GlmOcrIngestionService.PairResult result = bridge.ingestPair(fixtures, null);
        UUID paperId = result.examPaper().paperId();

        assertThat(result.questions()).isEqualTo(20);
        assertThat(result.parts()).isEqualTo(20);
        assertThat(result.markSchemes()).isEqualTo(20);
        assertThat(result.markPoints()).isEqualTo(51);

        ExamPaper paper = examPapers.findById(paperId).orElseThrow();
        assertThat(paper.validationState()).isEqualTo(ExamPaper.ValidationState.SUGGESTED);
        assertThat(paper.provenance()).isEqualTo(ExamPaper.Provenance.PAST_PAPER);
        assertThat(paper.questionPaperDocumentId())
                .isEqualTo(fixtures.qpCanonical().documentId());
        assertThat(paper.markSchemeDocumentId())
                .isEqualTo(fixtures.msCanonical().documentId());
        assertThat(paper.extractionMethod()).isEqualTo("glm-ocr-qp-v1+glm-ocr-ms-v1");
        assertThat(paper.sessionLabel()).isEqualTo("Summer 2025");

        // one version per question, linked to the paper, SUGGESTED, QP-provenance
        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        assertThat(versions).hasSize(20);
        for (QuestionVersion v : versions) {
            assertThat(v.validationState()).isEqualTo(QuestionVersion.ValidationState.SUGGESTED);
            assertThat(v.sourceDocumentId()).isEqualTo(fixtures.qpCanonical().documentId());
            assertThat(v.version()).isEqualTo(1);
            Question q = questions.findById(v.questionId()).orElseThrow();
            assertThat(q.examPaperId()).isEqualTo(paperId); // linked to the correct paper
            assertThat(q.provenance()).isEqualTo(Question.Provenance.PAST_PAPER);
        }

        // questions keep the parser identity (externalRef = questionId)
        assertThat(versions.stream().map(v -> questions.findById(v.questionId()).orElseThrow()
                .externalRef()).allMatch(ref -> ref.startsWith("q"))).isTrue();

        // schemes linked to versions, MS-provenance, SUGGESTED (points read through the
        // repository — the entity's points collection is lazy outside a session)
        List<MarkScheme> schemes = markSchemes.findByPaperId(paperId);
        assertThat(schemes).hasSize(20);
        for (MarkScheme s : schemes) {
            assertThat(s.validationState()).isEqualTo(MarkScheme.ValidationState.SUGGESTED);
            assertThat(s.sourceDocumentId()).isEqualTo(fixtures.msCanonical().documentId());
            assertThat(markPoints.findByMarkSchemeIdOrderByOrdering(s.id())).isNotEmpty();
        }

        // mark points carry question/part references; roman subparts resolved to parts
        List<MarkPoint> all = schemes.stream()
                .flatMap(s -> markPoints.findByMarkSchemeIdOrderByOrdering(s.id()).stream())
                .toList();
        assertThat(all).hasSize(51);
        assertThat(all.stream().filter(p -> p.questionPartId() != null)).isNotEmpty();
        assertThat(all.stream().map(MarkPoint::ref).filter(r -> r.contains("-"))).isNotEmpty();
    }

    // ── 3. the audited conflicts stay review-visible ────────────────────────────

    @Test
    @DisplayName("October pair: 20 questions; Q18 printed-total conflict stays review-visible")
    void octoberQ18ConflictStaysVisible() throws Exception {
        GlmOcrIngestionService.PairResult result =
                bridge.ingestPair(pair("october-2025-wph11-01"), null);
        assertThat(result.questions()).isEqualTo(20);
        assertThat(result.markPoints()).isEqualTo(53);

        // the parser's Q18 warning, verbatim — never repaired, never hidden
        GlmOcrBridgeRecord record = bridgeRecords
                .findByPaperId(result.examPaper().paperId()).orElseThrow();
        assertThat(record.reviewFindings())
                .contains("Q18: part marks sum (2) conflicts with printed total (8)");
        assertThat(record.reconciliationStatus()).isEqualTo("OK"); // no paper-total conflict…

        // …but the warning evidence is still a review finding
        List<ReviewFinding> findings = bridge
                .reviewFindingsForPaper(result.examPaper().paperId()).orElseThrow();
        assertThat(findings.stream().map(ReviewFinding::detail))
                .contains("Q18: part marks sum (2) conflicts with printed total (8)");

        // the QP draft evidence itself survives verbatim (marks 2, marksKnown false)
        JsonNode qpDraft = JSON.readTree(record.qpDraft());
        JsonNode q18 = java.util.stream.StreamSupport.stream(qpDraft.get("questions").spliterator(), false)
                .filter(q -> q.get("number").asInt() == 18).findFirst().orElseThrow();
        assertThat(q18.get("marks").asInt()).isEqualTo(2);
        assertThat(q18.get("marksKnown").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("1A pair: 19 questions; QP-80 vs MS-120 paper-total conflict preserved, never merged")
    void oneAPaperTotalConflictPreserved() throws Exception {
        GlmOcrIngestionService.PairResult result =
                bridge.ingestPair(pair("october-2025-wph11-01a"), null);
        assertThat(result.questions()).isEqualTo(19);
        assertThat(result.markPoints()).isEqualTo(69);

        assertThat(result.reconciliation().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reconciliation().paperTotalConflict()).isTrue();
        assertThat(result.reconciliation().qpPaperTotal()).isEqualTo(80);
        assertThat(result.reconciliation().msPaperTotal()).isEqualTo(120);

        GlmOcrBridgeRecord record = bridgeRecords
                .findByPaperId(result.examPaper().paperId()).orElseThrow();
        assertThat(record.reconciliationStatus()).isEqualTo("REVIEW_REQUIRED");

        // both totals preserved — never silently 80, never 120, never merged
        JsonNode reconciliation = JSON.readTree(record.reconciliation());
        assertThat(reconciliation.get("qpPaperTotal").asInt()).isEqualTo(80);
        assertThat(reconciliation.get("msPaperTotal").asInt()).isEqualTo(120);
        assertThat(reconciliation.get("paperTotalConflict").asBoolean()).isTrue();

        List<ReviewFinding> findings = bridge
                .reviewFindingsForPaper(result.examPaper().paperId()).orElseThrow();
        assertThat(findings.stream()
                .filter(f -> "paper-total-conflict".equals(f.severity()))
                .map(ReviewFinding::detail))
                .anySatisfy(d -> assertThat(d).contains("80").contains("120"));
    }

    // ── 3b. review-surface semantics (record existence ≠ finding count) ───────

    @Test
    @Order(45)
    @DisplayName("review surface: empty review_findings JSONB [] is present-empty, never 404")
    void emptyFindingsStayAPresentRecord() {
        // a clean pair (no conflicts, no warnings) legitimately persists an
        // empty findings array — that record exists and must never read as
        // "missing" (the old empty-list-as-sentinel bug). The paper is created
        // through the real T-011 path so the bridge record's FKs are honest.
        UUID paperId = pastPaperIngestion.ingest(new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IAL", "Physics",
                        "Unit 1", "clean-empty-findings-" + UUID.randomUUID().toString().substring(0, 8),
                        "WPH11/01-clean", "qp-doc-clean", "ms-doc-clean"),
                List.of(new PastPaperDraftDto.QuestionDraft("q1", "1", "stem",
                        "State", 2, "STRUCTURED", 1, 0.6,
                        List.of(new PastPaperDraftDto.PartDraft("a", "prompt",
                                "State", 2, 0.6)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "ms-doc-clean", List.of(
                        new PastPaperDraftDto.MarkPointDraft("1-a", 1, "content", 2,
                                List.of(), 0.6))),
                "it-test-method", true), null).paperId();

        bridgeRecords.save(new GlmOcrBridgeRecord(
                paperId, "qp-doc-empty-findings", "ms-doc-empty-findings",
                null, null,
                "qp-checksum", "ms-checksum",
                "glm-ocr-qp-v1+glm-ocr-ms-v1", "OK",
                "[]", "{}", "{}", "{}", null));

        var findings = bridge.reviewFindingsForPaper(paperId);
        assertThat(findings).isPresent();   // the record exists → not a 404
        assertThat(findings.get()).isEmpty(); // and its findings list is []

        // unknown paper ids still read as missing (the 404 path)
        assertThat(bridge.reviewFindingsForPaper(UUID.randomUUID())).isEmpty();
    }

    // ── 4. rerun idempotency ─────────────────────────────────────────────────────

    @Test
    @DisplayName("rerunning the exact same pair creates no second anything")
    void rerunIsIdempotent() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest october = pair("october-2025-wph11-01");
        GlmOcrIngestionService.PairResult first = bridge.ingestPair(october, null);
        Counts before = counts();

        GlmOcrIngestionService.PairResult second = bridge.ingestPair(october, null);

        assertThat(second.qpDocument().duplicate()).isTrue();
        assertThat(second.msDocument().duplicate()).isTrue();
        assertThat(second.examPaper().duplicate()).isTrue();
        assertThat(second.examPaper().paperId()).isEqualTo(first.examPaper().paperId());
        assertThat(second.questions()).isEqualTo(first.questions());
        assertThat(second.parts()).isEqualTo(first.parts());
        assertThat(second.markSchemes()).isEqualTo(first.markSchemes());
        assertThat(second.markPoints()).isEqualTo(first.markPoints());
        assertThat(second.embeddingSkipped()).isTrue();

        // no duplicate rows of any kind
        Counts after = counts();
        assertThat(after.documents).isEqualTo(before.documents);
        assertThat(after.chunks).isEqualTo(before.chunks);
        assertThat(after.bridgeRecords).isEqualTo(before.bridgeRecords);
        assertThat(after.versions).isEqualTo(before.versions);
        assertThat(after.schemes).isEqualTo(before.schemes);
        assertThat(after.papers).isEqualTo(before.papers);

        // one bridge record, keyed by the canonical pair identity
        GlmOcrBridgeRecord record = bridgeRecords.findByQpDocumentIdAndMsDocumentId(
                first.qpDocument().documentId(), first.msDocument().documentId()).orElseThrow();
        assertThat(record.paperId()).isEqualTo(first.examPaper().paperId());
    }

    // ── 5. servability gate + no implicit embedding ─────────────────────────────

    @Test
    @DisplayName("imported unvalidated content never appears in learner question selection")
    void unvalidatedContentNeverServes() throws Exception {
        GlmOcrIngestionService.PairResult result = bridge.ingestPair(pair("june-2025-wph11-01"), null);
        UUID paperId = result.examPaper().paperId();

        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        assertThat(versions).isNotEmpty();

        // the REAL serving projection: none of the imported questions serve
        Set<UUID> imported = versions.stream()
                .map(QuestionVersion::questionId).collect(Collectors.toSet());
        assertThat(learnerQuestions.list(null, null))
                .extracting(StudentQuestionView::id)
                .noneMatch(imported::contains);

        // direct fetch of an imported question refuses (unvalidated)
        UUID anyImported = imported.iterator().next();
        assertThatThrownBy(() -> learnerQuestions.get(anyImported))
                .isInstanceOf(NotFoundException.class);

        // and the spec itself agrees: SUGGESTED versions are not servable
        for (QuestionVersion v : versions) {
            Question q = questions.findById(v.questionId()).orElseThrow();
            assertThat(servable.isSatisfiedBy(q, v)).isFalse();
        }
    }
}
