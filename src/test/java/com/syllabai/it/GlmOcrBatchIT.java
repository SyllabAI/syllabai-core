package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionController;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.content.CanonicalDocumentValidator;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport;
import com.syllabai.teacher.ingestion.GlmOcrBatchService;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecord;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
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
 * T-C03 integration test: ONE controlled real-corpus batch through the
 * unchanged T-C02 bridge, against a real pgvector Postgres, driven by the six
 * REAL GLM-markdown-sample fixtures (three QP/MS pairs = the whole current
 * real GLM-OCR corpus). The batch report is the deliverable: first pass
 * ingests, the in-run idempotency pass and a full second batch run (a NEW
 * transaction) prove deterministic reruns, and every safety invariant is
 * verified against the database. The audit artifact is written to
 * {@code target/t-c03-batch-audit-report.json} — CI uploads it as the
 * human-review deliverable of the batch.
 *
 * <p>Two regression proofs beyond the happy path: (1) whole-batch atomicity —
 * a pair that fails AFTER its own rows were written rolls back the entire
 * batch (three fully-ingested pairs included); (2) the real learner serving
 * boundary — imported questions never appear in the actual
 * {@code QuestionController} selection (topic-scoped or not), direct fetches
 * refuse with 404, while authoritative seed content still serves (the gate
 * blocks unvalidated imports, not the endpoint).</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlmOcrBatchIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** Deterministic offline double (same as GlmOcrBridgeIT) — proves the
     *  batch never calls it: all chunks stay pending after the batch. */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        EmbeddingProvider fakeEmbeddingProvider() {
            return new ContentPipelineIT.HashingEmbeddingProvider();
        }
    }

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr");
    private static final List<String> PAIRS = List.of(
            "june-2025-wph11-01", "october-2025-wph11-01", "october-2025-wph11-01a");

    @Autowired
    private GlmOcrBatchService batchService;
    @Autowired
    private GlmOcrBridgeRecordRepository bridgeRecords;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private MarkPointRepository markPoints;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionController learnerQuestions;

    // ── 0. whole-batch atomicity: a late failure rolls back EVERYTHING ───────────

    @Test
    @Order(1)
    @DisplayName("one transaction: a late-failing pair rolls back the whole batch (three ingested pairs + its own partial writes)")
    void lateFailureRollsBackTheWholeBatch(@TempDir Path root) throws Exception {
        batchRoot(root);
        poisonLateFailureBundle(root.resolve("zzz-poisoned-duplicate-paper"));

        // pristine seed state before the batch (V7 seeds 8 demo-MCQ versions, nothing else)
        long seedVersions = questionVersions.count();
        assertThat(seedVersions).isEqualTo(8);
        assertThat(documents.count()).isZero();
        assertThat(examPapers.count()).isZero();
        assertThat(bridgeRecords.count()).isZero();

        // the poison strikes LATE: pairs 1-3 ingest fully, then the poisoned pair's
        // two canonical documents ingest BEFORE PastPaperIngestionService refuses
        // its duplicate paper (WPH11/01A + October 2025 = pair 3, same transaction) —
        // the exception is T-011's duplicate refusal, not early bundle validation
        assertThatThrownBy(() -> batchService.runBatch(root, null,
                GlmOcrBatchService.DEFAULT_MAX_PAIRS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already ingested");

        // ROLLBACK EVERYTHING: the three fully-processed pairs AND the poisoned
        // pair's own partial writes (its canonical documents + chunks) — nothing
        // of the batch survived; the database is the pristine seed again
        assertThat(documents.count()).as("documents").isZero();
        assertThat(chunks.count()).as("chunks").isZero();
        assertThat(examPapers.count()).as("papers").isZero();
        assertThat(questionVersions.count()).as("versions").isEqualTo(seedVersions);
        assertThat(markSchemes.count()).as("schemes").isZero();
        assertThat(markPoints.count()).as("mark points").isZero();
        assertThat(bridgeRecords.count()).as("bridge records").isZero();
    }

    // ── 1. the batch: first run ingests everything, all invariants pass ─────────

    @Test
    @Order(2)
    @DisplayName("first batch run: 3 real pairs INGESTED, all invariants verified, audit artifact written")
    void firstBatchRun(@TempDir Path root) throws Exception {
        batchRoot(root);

        GlmOcrBatchAuditReport report = batchService.runBatch(root, null,
                GlmOcrBatchService.DEFAULT_MAX_PAIRS);

        assertThat(report.pairCount()).isEqualTo(3);
        assertThat(report.maxPairs()).isEqualTo(GlmOcrBatchService.DEFAULT_MAX_PAIRS);
        assertThat(report.pairs()).extracting(p -> p.pairDir())
                .containsExactlyElementsOf(PAIRS); // sorted, deterministic order

        // first pass: all three pairs INGESTED (fresh database)
        assertThat(report.pairs()).allSatisfy(p ->
                assertThat(p.firstPass().paper()).isEqualTo("INGESTED"));
        // and every pair resolved to its existing rows in the idempotency pass
        assertThat(report.pairs()).allSatisfy(p -> {
            assertThat(p.idempotencyPass().paper()).isEqualTo("DUPLICATE");
            assertThat(p.idempotencyPass().paperId()).isEqualTo(p.firstPass().paperId());
        });

        // the real corpus counts (the T-C02-verified facts, now batch-borne).
        // October's MS draft has NO printed paper total (msPaperTotal null) —
        // an honest extraction gap, preserved verbatim like everything else.
        assertPair(report, 0, "june-2025-wph11-01", "OK", 20, 20, 20, 51, 80, 80);
        assertPair(report, 1, "october-2025-wph11-01", "OK", 20, 26, 20, 53, 80, null);
        assertPair(report, 2, "october-2025-wph11-01a", "REVIEW_REQUIRED", 19, 25, 19, 69,
                80, 120);

        // row-count evidence: pass 1 created exactly the expected rows (deltas
        // from `before` — the V7 seed migration already populates 8 demo-MCQ
        // question versions in a fresh database, and the audit counts honestly)
        assertThat(report.before().documents()).isZero();                  // no seed documents
        assertThat(report.afterFirstPass().documents() - report.before().documents())
                .isEqualTo(6);                                            // 3 pairs × QP+MS
        assertThat(report.afterFirstPass().examPapers() - report.before().examPapers())
                .isEqualTo(3);
        assertThat(report.afterFirstPass().bridgeRecords() - report.before().bridgeRecords())
                .isEqualTo(3);
        assertThat(report.afterFirstPass().questionVersions()
                - report.before().questionVersions()).isEqualTo(59);      // 20+20+19
        // …and the idempotency pass added nothing anywhere
        assertThat(report.afterIdempotencyPass().sameAs(report.afterFirstPass())).isTrue();

        // ALL five invariants verified against the real database
        assertThat(report.invariants())
                .extracting(GlmOcrBatchAuditReport.InvariantResult::invariant)
                .containsExactly("all-content-suggested", "no-implicit-embedding",
                        "not-learner-servable", "conflict-preservation",
                        "deterministic-rerun");
        assertThat(report.invariants())
                .allSatisfy(i -> assertThat(i.passed()).as(i.invariant()).isTrue());
        assertThat(report.allInvariantsPassed()).isTrue();

        // the audited conflicts are IN the report (never merged away)
        var oneA = report.pairs().get(2).firstPass();
        assertThat(oneA.paperTotalConflict()).isTrue();
        assertThat(oneA.qpPaperTotal()).isEqualTo(80);
        assertThat(oneA.msPaperTotal()).isEqualTo(120);
        assertThat(oneA.findings()).isPositive();

        // the human-review artifact: written, readable, faithful
        Path artifact = GlmOcrBatchAuditReport.writeArtifact(report,
                Path.of("target/t-c03-batch-audit"));
        GlmOcrBatchAuditReport parsed = GlmOcrBatchAuditReport.fromJson(
                Files.readString(artifact));
        assertThat(parsed).isEqualTo(report);

        // three bridge records exist — the durable review surface
        assertThat(bridgeRecords.count()).isEqualTo(3);
    }

    // ── 2. deterministic rerun: a full second batch run changes NOTHING ─────────

    @Test
    @Order(3)
    @DisplayName("second batch run (new transaction): everything DUPLICATE, zero new rows, invariants still pass")
    void secondBatchRunChangesNothing(@TempDir Path root) throws Exception {
        // the first run landed in test 1; this root holds the SAME bundles
        batchRoot(root);

        GlmOcrBatchAuditReport first = batchService.runBatch(root, null, 10);
        assertThat(first.pairs()).allSatisfy(p ->
                assertThat(p.firstPass().paper()).isEqualTo("DUPLICATE"));

        GlmOcrBatchAuditReport second = batchService.runBatch(root, null, 10);

        // zero new rows across every kind — the cross-transaction rerun proof
        assertThat(second.afterFirstPass().sameAs(first.before())).isTrue();
        assertThat(second.afterIdempotencyPass().sameAs(second.afterFirstPass())).isTrue();

        // and the batch is still fully safe to leave in place
        assertThat(second.allInvariantsPassed()).isTrue();

        // paper identities are stable across runs (deterministic resolution)
        for (int i = 0; i < 3; i++) {
            assertThat(second.pairs().get(i).firstPass().paperId())
                    .isEqualTo(first.pairs().get(i).firstPass().paperId());
        }
        assertThat(bridgeRecords.count()).isEqualTo(3); // still exactly three
    }

    // ── 3. the firehose guard ────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("a batch beyond the bound is refused before any row is written")
    void boundIsRefusedLoudly(@TempDir Path root) throws Exception {
        batchRoot(root);

        assertThatThrownBy(() -> batchService.runBatch(root, null, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the controlled bound");

        // still exactly three bridge records — the refused run wrote nothing
        assertThat(bridgeRecords.count()).isEqualTo(3);
    }

    // ── 4. the real learner-serving boundary (not a state-field check) ──────────

    @Test
    @Order(5)
    @DisplayName("real serving path: imported questions never serve (list + topic query + fetch 404); authoritative content still does")
    void importedBatchContentNeverServesToLearners() {
        // every question id the batch imported, from the durable bridge records
        Set<UUID> imported = new HashSet<>();
        for (GlmOcrBridgeRecord record : bridgeRecords.findAll()) {
            imported.addAll(questionVersions.findByPaperId(record.paperId()).stream()
                    .map(QuestionVersion::questionId).toList());
        }
        assertThat(imported).hasSize(59); // 20 + 20 + 19

        // the REAL serving projection: the learner selection excludes every import…
        List<StudentQuestionView> served = learnerQuestions.list(null, null);
        assertThat(served).extracting(StudentQuestionView::id).noneMatch(imported::contains);
        // …while authoritative content remains servable (the gate blocks
        // unvalidated imports, not the endpoint — 8 seed MCQs still serve)
        assertThat(served).isNotEmpty();

        // the topic-scoped practice query (the anchor topic of an imported paper):
        // its questions are found by topic, then refused by the servable spec
        UUID anyImported = imported.iterator().next();
        UUID anchorTopic = questions.findById(anyImported).orElseThrow().primaryTopicNodeId();
        assertThat(learnerQuestions.list(anchorTopic, null)).isEmpty();

        // direct fetch of an imported question refuses (unvalidated → 404)…
        assertThatThrownBy(() -> learnerQuestions.get(anyImported))
                .isInstanceOf(NotFoundException.class);
        // …while direct fetch of an authoritative question serves
        UUID authoritative = served.get(0).id();
        assertThat(learnerQuestions.get(authoritative).id()).isEqualTo(authoritative);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** the real 3-pair batch root (a README proves non-pair entries are skipped) */
    private static void batchRoot(Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "T-C03 controlled batch: 3 WPH11 pairs");
        for (String pair : PAIRS) {
            Path dir = root.resolve(pair);
            Files.createDirectories(dir);
            for (String file : GlmOcrBatchService.BUNDLE_FILES) {
                Files.copy(FIXTURES.resolve(pair).resolve(file), dir.resolve(file));
            }
        }
    }

    /**
     * A LATE-STAGE poison (the atomicity regression): a copy of the 1A bundle
     * re-identified so its canonical documents ingest FRESH (new documentId +
     * checksum, claimed by the patched drafts — bundle validation passes),
     * after which PastPaperIngestionService refuses the duplicate paper
     * (WPH11/01A + October 2025, ingested by pair 3 earlier in the same
     * transaction). The failure therefore arrives after real rows were
     * written — exactly the shape whole-batch rollback must survive.
     */
    private static void poisonLateFailureBundle(Path target) throws IOException {
        Files.createDirectories(target);
        for (String file : GlmOcrBatchService.BUNDLE_FILES) {
            Files.copy(FIXTURES.resolve("october-2025-wph11-01a").resolve(file),
                    target.resolve(file));
        }
        reIdentify(target.resolve("qp-canonical.json"), target.resolve("qp-draft.json"),
                "qp");
        reIdentify(target.resolve("ms-canonical.json"), target.resolve("ms-draft.json"),
                "ms");
    }

    /** fresh canonical identity (documentId + source checksum) that the draft
     *  claims — the bundle stays internally consistent, only its identity differs.
     *  P-6 (recovery 2026-09-13): the documentId must be RE-DERIVED from the
     *  fresh checksum (same engine/engineVersion as the original canonical) —
     *  a hand-crafted id now fails canonical validation at ingest, which would
     *  arrive BEFORE the late duplicate-paper conflict this test exists to
     *  exercise. */
    private static void reIdentify(Path canonicalFile, Path draftFile, String side)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String freshChecksum = "deadbeef0000400080000000000000" + ("qp".equals(side) ? "1" : "2");

        ObjectNode canonical = (ObjectNode) mapper.readTree(Files.readString(canonicalFile));
        JsonNode provenance = canonical.path("provenance");
        String freshDocumentId = CanonicalDocumentValidator.derivedDocumentId(
                freshChecksum,
                provenance.path("engine").asText(null),
                provenance.path("engineVersion").asText(null));
        canonical.put("documentId", freshDocumentId);
        ((ObjectNode) canonical.get("source")).put("checksum", freshChecksum);
        Files.writeString(canonicalFile, mapper.writeValueAsString(canonical));

        ObjectNode draft = (ObjectNode) mapper.readTree(Files.readString(draftFile));
        ((ObjectNode) draft.get("paper")).put("canonicalDocumentId", freshDocumentId);
        Files.writeString(draftFile, mapper.writeValueAsString(draft));
    }

    private static void assertPair(GlmOcrBatchAuditReport report, int index,
                                   String pairDir, String reconciliation,
                                   int questions, int parts, int schemes, int points,
                                   Integer qpTotal, Integer msTotal) {
        var pair = report.pairs().get(index).firstPass();
        assertThat(pair.questions()).as("%s questions", pairDir).isEqualTo(questions);
        assertThat(pair.parts()).as("%s parts", pairDir).isEqualTo(parts);
        assertThat(pair.markSchemes()).as("%s schemes", pairDir).isEqualTo(schemes);
        assertThat(pair.markPoints()).as("%s points", pairDir).isEqualTo(points);
        assertThat(pair.reconciliationStatus()).as("%s status", pairDir)
                .isEqualTo(reconciliation);
        assertThat(pair.qpPaperTotal()).as("%s qpTotal", pairDir).isEqualTo(qpTotal);
        assertThat(pair.msPaperTotal()).as("%s msTotal", pairDir).isEqualTo(msTotal);
        assertThat(pair.embeddingSkipped()).as("%s embedding", pairDir).isTrue();
        assertThat(pair.qpChunks()).as("%s qp chunks", pairDir).isPositive();
        assertThat(pair.msChunks()).as("%s ms chunks", pairDir).isPositive();
    }
}
