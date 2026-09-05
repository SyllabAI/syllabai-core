package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.InvariantResult;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.PairAudit;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.PairOutcome;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.RowCounts;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.GlmOcrPairRequest;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.PairResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-C03 — ONE controlled real-corpus ingestion batch over the existing T-C02
 * bridge (the bridge itself is untouched: one pair, one transaction slot in
 * the batch, same {@link GlmOcrIngestionService#ingestPair} entry point).
 *
 * <p><strong>Bounded by design, never a corpus firehose:</strong> the batch
 * root must contain a small set of pair directories; anything beyond
 * {@code maxPairs} (default {@value #DEFAULT_MAX_PAIRS}) is refused before a
 * single row is written. The whole batch is ONE transaction — all pairs land
 * or none do.</p>
 *
 * <p><strong>The audit is the deliverable:</strong> besides ingesting, the
 * run VERIFIES the batch-level safety invariants against the database (not
 * against service claims) and assembles a {@link GlmOcrBatchAuditReport} for
 * human review: all-SUGGESTED imports, no implicit embedding, no learner
 * servability, conflict preservation (reconciliation + findings verbatim),
 * and deterministic reruns (an in-run idempotency pass plus row-count
 * evidence; cross-transaction rerun safety is additionally covered by the
 * integration test running the whole batch twice).</p>
 */
@Service
public class GlmOcrBatchService {

    private static final Logger log = LoggerFactory.getLogger(GlmOcrBatchService.class);

    /** firehose guard: a controlled batch is small by contract (T-C03 scope) */
    public static final int DEFAULT_MAX_PAIRS = 10;

    /** the five parser outputs one pair bundle must provide (the T-C02 contract) */
    public static final List<String> BUNDLE_FILES = List.of(
            "qp-canonical.json", "ms-canonical.json",
            "qp-draft.json", "ms-draft.json", "reconciliation.json");

    /** house pattern: the app context exposes no ObjectMapper bean */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final GlmOcrIngestionService bridge;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final MarkPointRepository markPoints;
    private final GlmOcrBridgeRecordRepository bridgeRecords;

    public GlmOcrBatchService(GlmOcrIngestionService bridge,
                              DocumentRepository documents,
                              DocumentChunkRepository chunks,
                              ExamPaperRepository examPapers,
                              QuestionVersionRepository questionVersions,
                              MarkSchemeRepository markSchemes,
                              MarkPointRepository markPoints,
                              GlmOcrBridgeRecordRepository bridgeRecords) {
        this.bridge = bridge;
        this.documents = documents;
        this.chunks = chunks;
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
        this.bridgeRecords = bridgeRecords;
    }

    /**
     * Runs one controlled batch: discovery → bound check → first pass (each
     * pair through {@link GlmOcrIngestionService#ingestPair}) → in-run
     * idempotency pass → DB-level invariant verification → audit report.
     * ONE transaction: a failing pair rolls back the whole batch.
     *
     * @param root     directory holding one sub-directory per QP/MS pair
     *                 (the five parser outputs each; unrelated files such as
     *                 a README are skipped)
     * @param operator authenticated operator or null for ops-CLI runs
     * @param maxPairs hard upper bound — refuse more, never truncate silently
     */
    @Transactional
    public GlmOcrBatchAuditReport runBatch(Path root, UUID operator, int maxPairs) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("syllabai.glmocr batch root is not a directory: " + root);
        }
        List<Path> pairDirs = discoverPairDirs(root);
        if (pairDirs.isEmpty()) {
            throw new ConflictException("no pair bundles found under " + root
                    + " — expected sub-directories with the five parser outputs ("
                    + String.join(", ", BUNDLE_FILES) + ")");
        }
        if (pairDirs.size() > maxPairs) {
            throw new IllegalStateException("batch exceeds the controlled bound: "
                    + pairDirs.size() + " pair bundles under " + root
                    + " but maxPairs=" + maxPairs + " — T-C03 batches are bounded by contract,"
                    + " split the corpus into reviewed batches instead of raising the bound");
        }

        Instant executedAt = Instant.now();
        RowCounts before = snapshot();
        log.info("glm-ocr batch: {} pair(s) under {} (bound {}), row counts before: documents={},"
                        + " chunks={}, papers={}, versions={}, schemes={}, points={}, bridgeRecords={}",
                pairDirs.size(), root, maxPairs, before.documents(), before.chunks(),
                before.examPapers(), before.questionVersions(), before.markSchemes(),
                before.markPoints(), before.bridgeRecords());

        // first pass — every pair through the UNCHANGED bridge entry point
        List<PairResult> firstPass = new ArrayList<>(pairDirs.size());
        List<PairAudit> pairAudits = new ArrayList<>(pairDirs.size());
        for (Path pairDir : pairDirs) {
            PairResult result = bridge.ingestPair(loadBundle(pairDir), operator);
            firstPass.add(result);
            log.info("glm-ocr batch first pass: {} → paper {} ({}), reconciliation {},"
                            + " findings {}", pairDir.getFileName(), result.examPaper().paperId(),
                    result.examPaper().duplicate() ? "DUPLICATE" : "INGESTED",
                    result.reconciliation().status(), result.reviewFindings().size());
        }
        RowCounts afterFirstPass = snapshot();

        // in-run idempotency pass — the same resolution a rerun takes,
        // recorded in the audit itself (cross-transaction rerun is covered by
        // the integration test, which runs the whole batch twice)
        for (int i = 0; i < pairDirs.size(); i++) {
            PairResult rerun = bridge.ingestPair(loadBundle(pairDirs.get(i)), operator);
            pairAudits.add(new PairAudit(pairDirs.get(i).getFileName().toString(),
                    PairOutcome.from(firstPass.get(i)), PairOutcome.from(rerun)));
        }
        RowCounts afterIdempotencyPass = snapshot();

        List<InvariantResult> invariants = List.of(
                verifyAllContentSuggested(firstPass),
                verifyNoImplicitEmbedding(firstPass),
                verifyNotLearnerServable(firstPass),
                verifyConflictPreservation(firstPass),
                verifyDeterministicRerun(pairAudits, afterFirstPass, afterIdempotencyPass));

        boolean allPassed = invariants.stream().allMatch(InvariantResult::passed);
        GlmOcrBatchAuditReport report = new GlmOcrBatchAuditReport(
                GlmOcrDraftMapper.BRIDGE_METHOD,
                root.toAbsolutePath().normalize().toString(),
                maxPairs, pairDirs.size(), executedAt, operator,
                List.copyOf(pairAudits), invariants,
                before, afterFirstPass, afterIdempotencyPass, allPassed);

        if (allPassed) {
            log.info("glm-ocr batch complete: {} pair(s), ALL invariants passed", pairDirs.size());
        } else {
            log.error("glm-ocr batch complete: {} pair(s), INVARIANT FAILURES: {}",
                    pairDirs.size(), invariants.stream()
                            .filter(i -> !i.passed()).map(InvariantResult::invariant).toList());
        }
        return report;
    }

    /**
     * Discovers pair directories: a sub-directory holding ALL five bundle
     * files is a pair; one holding SOME of them is a fail-loud contract
     * violation (a half-written bundle must never be silently skipped);
     * one holding none (e.g. a README) is not a pair and is skipped.
     * Sorted by name for deterministic order.
     */
    static List<Path> discoverPairDirs(Path root) {
        try (var stream = Files.list(root)) {
            List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            List<Path> pairs = new ArrayList<>();
            for (Path dir : dirs) {
                long present = BUNDLE_FILES.stream()
                        .filter(file -> Files.isRegularFile(dir.resolve(file))).count();
                if (present == BUNDLE_FILES.size()) {
                    pairs.add(dir);
                } else if (present > 0) {
                    throw new ConflictException("incomplete pair bundle in " + dir + ": "
                            + present + "/" + BUNDLE_FILES.size()
                            + " parser outputs present — refusing to guess (expected: "
                            + String.join(", ", BUNDLE_FILES) + ")");
                }
                // present == 0 → not a pair directory (README etc.) — skipped
            }
            return pairs;
        } catch (IOException e) {
            throw new UncheckedIOException("batch root is not listable: " + root, e);
        }
    }

    /** loads the five parser outputs of one pair — the exact bridge contract */
    static GlmOcrPairRequest loadBundle(Path pairDir) {
        try {
            String qpCanonicalJson = Files.readString(pairDir.resolve("qp-canonical.json"));
            String msCanonicalJson = Files.readString(pairDir.resolve("ms-canonical.json"));
            return new GlmOcrPairRequest(
                    JSON.readValue(qpCanonicalJson, CanonicalDocumentDto.class),
                    qpCanonicalJson,
                    JSON.readValue(msCanonicalJson, CanonicalDocumentDto.class),
                    msCanonicalJson,
                    JSON.readValue(pairDir.resolve("qp-draft.json").toFile(),
                            GlmOcrPaperDraftDto.class),
                    JSON.readValue(pairDir.resolve("ms-draft.json").toFile(),
                            GlmOcrMarkSchemeDraftDto.class),
                    JSON.readValue(pairDir.resolve("reconciliation.json").toFile(),
                            GlmOcrReconciliationDto.class));
        } catch (IOException e) {
            throw new UncheckedIOException("pair bundle is not readable: " + pairDir, e);
        }
    }

    private RowCounts snapshot() {
        return new RowCounts(documents.count(), chunks.count(), examPapers.count(),
                questionVersions.count(), markSchemes.count(), markPoints.count(),
                bridgeRecords.count());
    }

    // ── batch-level invariant verification (DB queries, not service claims) ────

    /** everything the batch imported must still be SUGGESTED — nothing leaked
     *  into teacher-validated, let alone learner-servable, state */
    private InvariantResult verifyAllContentSuggested(List<PairResult> firstPass) {
        long papers = 0;
        long versions = 0;
        long schemes = 0;
        List<String> violations = new ArrayList<>();
        for (PairResult result : firstPass) {
            UUID paperId = result.examPaper().paperId();
            ExamPaper paper = examPapers.findById(paperId).orElse(null);
            if (paper == null || paper.validationState() != ExamPaper.ValidationState.SUGGESTED) {
                violations.add("paper " + paperId + " is not SUGGESTED");
                continue;
            }
            papers++;
            for (QuestionVersion v : questionVersions.findByPaperId(paperId)) {
                versions++;
                if (v.validationState() != QuestionVersion.ValidationState.SUGGESTED) {
                    violations.add("question version " + v.id() + " is not SUGGESTED");
                }
            }
            for (MarkScheme s : markSchemes.findByPaperId(paperId)) {
                schemes++;
                if (s.validationState() != MarkScheme.ValidationState.SUGGESTED) {
                    violations.add("mark scheme " + s.id() + " is not SUGGESTED");
                }
            }
        }
        String detail = papers + " paper(s), " + versions + " question version(s), "
                + schemes + " mark scheme(s) — all SUGGESTED (mark points follow their scheme)";
        return new InvariantResult("all-content-suggested", violations.isEmpty(), detail);
    }

    /** the bridge never embeds: every chunk of the batch's documents is pending */
    private InvariantResult verifyNoImplicitEmbedding(List<PairResult> firstPass) {
        long chunkTotal = 0;
        long pendingTotal = 0;
        List<String> violations = new ArrayList<>();
        for (PairResult result : firstPass) {
            for (var document : List.of(result.qpDocument(), result.msDocument())) {
                List<DocumentChunk> docChunks =
                        chunks.findByDocumentRowIdOrderByChunkIndexAsc(document.rowId());
                chunkTotal += docChunks.size();
                for (DocumentChunk chunk : docChunks) {
                    if (chunk.embeddedAt() == null) {
                        pendingTotal++;
                    } else {
                        violations.add("chunk " + chunk.id() + " is embedded ("
                                + chunk.embeddingModel() + ") — embedding must stay explicit");
                    }
                }
            }
        }
        String detail = pendingTotal + "/" + chunkTotal
                + " chunk(s) pending (embeddedAt null) across the batch documents";
        return new InvariantResult("no-implicit-embedding", violations.isEmpty(), detail);
    }

    /** servable questions require VALIDATED versions; imported ones stay
     *  SUGGESTED, so nothing from this batch can reach a learner */
    private InvariantResult verifyNotLearnerServable(List<PairResult> firstPass) {
        long versions = 0;
        long validated = 0;
        for (PairResult result : firstPass) {
            for (QuestionVersion v : questionVersions.findByPaperId(
                    result.examPaper().paperId())) {
                versions++;
                if (v.validationState() == QuestionVersion.ValidationState.VALIDATED) {
                    validated++;
                }
            }
        }
        String detail = validated + " VALIDATED version(s) among " + versions
                + " imported version(s) — servable spec requires VALIDATED, so none serves";
        return new InvariantResult("not-learner-servable", validated == 0, detail);
    }

    /** the bridge records keep what the parser found: reconciliation status and
     *  findings counts must match the pair results — REVIEW_REQUIRED and the
     *  audited conflicts are never merged away or dropped */
    private InvariantResult verifyConflictPreservation(List<PairResult> firstPass) {
        List<String> violations = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        List<Integer> findingCounts = new ArrayList<>();
        for (PairResult result : firstPass) {
            UUID paperId = result.examPaper().paperId();
            GlmOcrBridgeRecord record = bridgeRecords.findByPaperId(paperId).orElse(null);
            if (record == null) {
                violations.add("bridge record missing for paper " + paperId);
                continue;
            }
            statuses.add(record.reconciliationStatus());
            findingCounts.add(countFindings(record.reviewFindings()));
            if (!record.reconciliationStatus().equals(result.reconciliation().status())) {
                violations.add("paper " + paperId + ": record status "
                        + record.reconciliationStatus() + " != pair result "
                        + result.reconciliation().status());
            }
            if (countFindings(record.reviewFindings()) != result.reviewFindings().size()) {
                violations.add("paper " + paperId + ": stored findings count "
                        + countFindings(record.reviewFindings()) + " != pair result "
                        + result.reviewFindings().size());
            }
        }
        String detail = firstPass.size() + " bridge record(s); statuses ("
                + String.join(", ", statuses) + "); findings " + findingCounts
                + " — reconciliation and findings persisted verbatim";
        return new InvariantResult("conflict-preservation", violations.isEmpty(), detail);
    }

    /** the in-run rerun resolved every pair to its existing rows: all papers
     *  DUPLICATE and the row counts after the pass equal the counts before it */
    private InvariantResult verifyDeterministicRerun(List<PairAudit> pairAudits,
                                                     RowCounts afterFirstPass,
                                                     RowCounts afterIdempotencyPass) {
        long duplicates = pairAudits.stream()
                .filter(a -> "DUPLICATE".equals(a.idempotencyPass().paper()))
                .count();
        List<String> violations = new ArrayList<>();
        if (duplicates != pairAudits.size()) {
            violations.add(duplicates + "/" + pairAudits.size()
                    + " papers DUPLICATE in the idempotency pass");
        }
        if (!afterFirstPass.sameAs(afterIdempotencyPass)) {
            violations.add("row counts changed during the idempotency pass: "
                    + afterFirstPass + " → " + afterIdempotencyPass);
        }
        String detail = "idempotency pass: " + duplicates + "/" + pairAudits.size()
                + " paper(s) DUPLICATE, 0 new rows across all row kinds"
                + " (cross-transaction rerun is covered by the batch integration test)";
        return new InvariantResult("deterministic-rerun", violations.isEmpty(), detail);
    }

    private static int countFindings(String storedFindingsJson) {
        try {
            JsonNode node = JSON.readTree(storedFindingsJson);
            return node.isArray() ? node.size() : -1;
        } catch (IOException e) {
            return -1;
        }
    }
}
