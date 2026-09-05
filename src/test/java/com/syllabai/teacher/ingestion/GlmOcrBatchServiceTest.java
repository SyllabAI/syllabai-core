package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.InvariantResult;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.DocumentStatus;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.PairResult;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.PaperStatus;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.ReconciliationStatus;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper.ReviewFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-C03 batch orchestration: bounded discovery, the two-pass structure, the
 * DB-verified invariants and the audit report assembly. Pinned against the
 * REAL parser fixture bundles (June + October WPH11 pairs); the bridge and
 * repositories are test doubles here — the real-corpus end-to-end proof is
 * {@code GlmOcrBatchIT}.
 */
class GlmOcrBatchServiceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr");

    private final GlmOcrIngestionService bridge = mock(GlmOcrIngestionService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);
    private final GlmOcrBridgeRecordRepository bridgeRecords =
            mock(GlmOcrBridgeRecordRepository.class);

    private GlmOcrBatchService service() {
        return new GlmOcrBatchService(bridge, documents, chunks, examPapers,
                questionVersions, markSchemes, markPoints, bridgeRecords);
    }

    // ── discovery ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("discovery: only complete five-file bundles are pairs; order is deterministic")
    void discoverySkipsNonPairDirs(@TempDir Path root) throws IOException {
        copyPair(root.resolve("b-october"), "october-2025-wph11-01");
        copyPair(root.resolve("a-june"), "june-2025-wph11-01");
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("notes/README.md"), "operator notes");
        Files.writeString(root.resolve("README.md"), "batch root readme");

        List<Path> pairs = GlmOcrBatchService.discoverPairDirs(root);

        assertThat(pairs).extracting(p -> p.getFileName().toString())
                .containsExactly("a-june", "b-october"); // sorted, notes/ skipped
    }

    @Test
    @DisplayName("discovery: a half-written bundle fails loud — never silently skipped")
    void discoveryFailsLoudOnIncompleteBundle(@TempDir Path root) throws IOException {
        Path half = root.resolve("broken-pair");
        copyPair(half, "june-2025-wph11-01");
        Files.delete(half.resolve("reconciliation.json"));

        assertThatThrownBy(() -> GlmOcrBatchService.discoverPairDirs(root))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("incomplete pair bundle")
                .hasMessageContaining("4/5");
    }

    // ── bounds (the firehose guard) ──────────────────────────────────────────────

    @Test
    @DisplayName("a batch beyond the bound is refused before any bridge call")
    void refusesBeyondBound(@TempDir Path root) throws IOException {
        copyPair(root.resolve("june"), "june-2025-wph11-01");
        copyPair(root.resolve("october"), "october-2025-wph11-01");
        copyPair(root.resolve("one-a"), "october-2025-wph11-01a");

        assertThatThrownBy(() -> service().runBatch(root, null, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds the controlled bound")
                .hasMessageContaining("3")
                .hasMessageContaining("maxPairs=2");
        verify(bridge, never()).ingestPair(any(), isNull());
    }

    @Test
    @DisplayName("a batch root without any pair bundle fails loud")
    void refusesEmptyRoot(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "nothing here");

        assertThatThrownBy(() -> service().runBatch(root, null, 10))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no pair bundles found");
    }

    // ── the audited batch run ────────────────────────────────────────────────────

    @Test
    @DisplayName("two real fixture pairs: two passes per pair, all invariants verified, report assembled")
    void happyPathProducesAuditedReport(@TempDir Path root) throws IOException {
        copyPair(root.resolve("june"), "june-2025-wph11-01");
        copyPair(root.resolve("october"), "october-2025-wph11-01");

        UUID junePaper = UUID.randomUUID();
        UUID octoberPaper = UUID.randomUUID();
        stubBridge(junePaper, octoberPaper);
        stubInvariantQueries(junePaper, octoberPaper);
        stubCounts(0, 2); // empty database → batch lands 2 (per-kind) rows

        GlmOcrBatchAuditReport report = service().runBatch(root, null, 10);

        assertThat(report.pairCount()).isEqualTo(2);
        assertThat(report.maxPairs()).isEqualTo(10);
        assertThat(report.pairs()).extracting(p -> p.pairDir())
                .containsExactly("june", "october");

        // two passes per pair, both through the UNCHANGED bridge entry point
        verify(bridge, org.mockito.Mockito.times(4)).ingestPair(any(), isNull());

        // first pass INGESTED, idempotency pass DUPLICATE — per pair
        assertThat(report.pairs()).allSatisfy(p -> {
            assertThat(p.firstPass().paper()).isEqualTo("INGESTED");
            assertThat(p.idempotencyPass().paper()).isEqualTo("DUPLICATE");
            assertThat(p.idempotencyPass().paperId()).isEqualTo(p.firstPass().paperId());
            assertThat(p.idempotencyPass().questions()).isEqualTo(p.firstPass().questions());
            assertThat(p.firstPass().embeddingSkipped()).isTrue();
        });

        // June is clean; October carries the Q18 warning finding
        var june = report.pairs().get(0).firstPass();
        var october = report.pairs().get(1).firstPass();
        assertThat(june.reconciliationStatus()).isEqualTo("OK");
        assertThat(june.findings()).isZero();
        assertThat(october.reconciliationStatus()).isEqualTo("OK");
        assertThat(october.findings()).isEqualTo(1);

        // all five invariants present and passed
        assertThat(report.invariants()).extracting(InvariantResult::invariant).containsExactly(
                "all-content-suggested", "no-implicit-embedding", "not-learner-servable",
                "conflict-preservation", "deterministic-rerun");
        assertThat(report.invariants()).allSatisfy(i -> {
            assertThat(i.passed()).as(i.invariant()).isTrue();
            assertThat(i.detail()).as(i.invariant()).isNotBlank();
        });
        assertThat(report.allInvariantsPassed()).isTrue();

        // row-count evidence: identical snapshots → zero new rows in pass 2
        assertThat(report.afterFirstPass().sameAs(report.afterIdempotencyPass())).isTrue();
        assertThat(report.before().sameAs(report.afterFirstPass())).isFalse(); // pass 1 added rows

        // the audit artifact round-trips through JSON (the human-review surface)
        GlmOcrBatchAuditReport parsed = GlmOcrBatchAuditReport.fromJson(report.toJson());
        assertThat(parsed).isEqualTo(report);
    }

    @Test
    @DisplayName("a leaked VALIDATED version fails the servability invariant — the report says so")
    void validatedLeakFailsTheReport(@TempDir Path root) throws IOException {
        copyPair(root.resolve("june"), "june-2025-wph11-01");

        UUID junePaper = UUID.randomUUID();
        stubBridge(junePaper, UUID.randomUUID());
        stubInvariantQueries(junePaper, UUID.randomUUID());
        stubCounts(0, 1);
        // the leak: one imported version claims VALIDATED
        QuestionVersion leaked = mock(QuestionVersion.class);
        lenient().when(leaked.validationState())
                .thenReturn(QuestionVersion.ValidationState.VALIDATED);
        lenient().when(questionVersions.findByPaperId(junePaper)).thenReturn(List.of(leaked));

        GlmOcrBatchAuditReport report = service().runBatch(root, null, 10);

        assertThat(report.allInvariantsPassed()).isFalse();
        var servability = report.invariants().stream()
                .filter(i -> "not-learner-servable".equals(i.invariant())).findFirst().orElseThrow();
        assertThat(servability.passed()).isFalse();
        assertThat(servability.detail()).contains("1 VALIDATED");
    }

    // ── stubbing ────────────────────────────────────────────────────────────────

    /** first call per pair → INGESTED result; second call → the DUPLICATE result */
    private void stubBridge(UUID junePaper, UUID octoberPaper) {
        Map<String, AtomicInteger> calls = new HashMap<>();
        when(bridge.ingestPair(any(), isNull())).thenAnswer(invocation -> {
            GlmOcrIngestionService.GlmOcrPairRequest request = invocation.getArgument(0);
            boolean june = request.qpCanonical().source().uri().contains("June");
            String key = june ? "june" : "october";
            boolean first = calls.computeIfAbsent(key, k -> new AtomicInteger())
                    .incrementAndGet() == 1;
            return june
                    ? juneResult(junePaper, first)
                    : octoberResult(octoberPaper, first);
        });
    }

    private PairResult juneResult(UUID paperId, boolean first) {
        return new PairResult(
                new DocumentStatus(!first, "june-qp-doc", UUID.randomUUID(), 38),
                new DocumentStatus(!first, "june-ms-doc", UUID.randomUUID(), 14),
                new PaperStatus(!first, paperId, "June 2025 WPH11/01"),
                20, 20, 20, 51, 38, 14,
                new ReconciliationStatus("OK", 0, false, 80, 80),
                List.of(), true);
    }

    private PairResult octoberResult(UUID paperId, boolean first) {
        return new PairResult(
                new DocumentStatus(!first, "october-qp-doc", UUID.randomUUID(), 38),
                new DocumentStatus(!first, "october-ms-doc", UUID.randomUUID(), 12),
                new PaperStatus(!first, paperId, "October 2025 WPH11/01"),
                20, 26, 20, 53, 38, 12,
                new ReconciliationStatus("OK", 0, false, 80, 80),
                List.of(new ReviewFinding("qp-warning", "question-marks-mismatch", "18",
                        2, 8, "Q18: part marks sum (2) conflicts with printed total (8)")),
                true);
    }

    /** DB-shaped doubles for the invariant verification queries */
    private void stubInvariantQueries(UUID junePaper, UUID octoberPaper) {
        for (UUID paperId : List.of(junePaper, octoberPaper)) {
            ExamPaper paper = mock(ExamPaper.class);
            lenient().when(paper.validationState()).thenReturn(ExamPaper.ValidationState.SUGGESTED);
            lenient().when(examPapers.findById(paperId)).thenReturn(java.util.Optional.of(paper));

            QuestionVersion version = mock(QuestionVersion.class);
            lenient().when(version.validationState())
                    .thenReturn(QuestionVersion.ValidationState.SUGGESTED);
            lenient().when(version.id()).thenReturn(UUID.randomUUID());
            lenient().when(questionVersions.findByPaperId(paperId)).thenReturn(List.of(version));

            MarkScheme scheme = mock(MarkScheme.class);
            lenient().when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.SUGGESTED);
            lenient().when(scheme.id()).thenReturn(UUID.randomUUID());
            lenient().when(markSchemes.findByPaperId(paperId)).thenReturn(List.of(scheme));
        }

        // pending chunks only — the bridge never embeds
        DocumentChunk chunk = mock(DocumentChunk.class);
        lenient().when(chunk.id()).thenReturn(UUID.randomUUID());
        lenient().when(chunk.embeddedAt()).thenReturn(null);
        lenient().when(chunk.embeddingModel()).thenReturn(null);
        lenient().when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(any()))
                .thenReturn(List.of(chunk));

        // bridge records matching the pair results (status + findings counts)
        lenient().when(bridgeRecords.findByPaperId(junePaper))
                .thenReturn(java.util.Optional.of(bridgeRecord("OK", "[]")));
        lenient().when(bridgeRecords.findByPaperId(octoberPaper))
                .thenReturn(java.util.Optional.of(bridgeRecord("OK",
                        "[{\"source\":\"qp-warning\",\"severity\":\"question-marks-mismatch\","
                                + "\"questionNumber\":\"18\",\"qpMarks\":2,\"msMarks\":8,"
                                + "\"detail\":\"Q18: part marks sum (2) conflicts with printed"
                                + " total (8)\"}]")));
    }

    private GlmOcrBridgeRecord bridgeRecord(String status, String findings) {
        return new GlmOcrBridgeRecord(UUID.randomUUID(), "qp-doc", "ms-doc",
                null, null, "qp-check", "ms-check",
                GlmOcrDraftMapper.BRIDGE_METHOD, status, findings,
                "{}", "{}", "{}", null);
    }

    /** count() doubles: first snapshot (before) → base; after pass 1 → base+delta
     *  (the batch landed); after the idempotency pass → unchanged (base+delta) */
    private void stubCounts(long base, long delta) {
        statefulCount(documents, base, delta);
        statefulCount(chunks, base, delta);
        statefulCount(examPapers, base, delta);
        statefulCount(questionVersions, base, delta);
        statefulCount(markSchemes, base, delta);
        statefulCount(markPoints, base, delta);
        statefulCount(bridgeRecords, base, delta);
    }

    private static void statefulCount(
            org.springframework.data.jpa.repository.JpaRepository<?, ?> repo,
            long base, long delta) {
        AtomicInteger calls = new AtomicInteger();
        lenient().when(repo.count()).thenAnswer(invocation ->
                calls.incrementAndGet() <= 1 ? base : base + delta);
    }

    private static void copyPair(Path target, String fixtureName) throws IOException {
        Files.createDirectories(target);
        for (String file : GlmOcrBatchService.BUNDLE_FILES) {
            Files.copy(FIXTURES.resolve(fixtureName).resolve(file), target.resolve(file));
        }
    }
}
