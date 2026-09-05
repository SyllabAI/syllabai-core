package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.content.EmbeddingProvider;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport;
import com.syllabai.teacher.ingestion.GlmOcrBatchService;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    // ── 1. the batch: first run ingests everything, all invariants pass ─────────

    @Test
    @Order(1)
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
    @Order(2)
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
    @Order(3)
    @DisplayName("a batch beyond the bound is refused before any row is written")
    void boundIsRefusedLoudly(@TempDir Path root) throws Exception {
        batchRoot(root);

        assertThatThrownBy(() -> batchService.runBatch(root, null, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the controlled bound");

        // still exactly three bridge records — the refused run wrote nothing
        assertThat(bridgeRecords.count()).isEqualTo(3);
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
