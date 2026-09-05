package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper.ReviewFinding;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-C02 — the controlled bridge from VERIFIED GLM-OCR parser outputs into the
 * existing ingestion fabric. One pair, one transaction, one entry point:
 *
 * <pre>
 * parser canonical QP JSON ┐
 * parser canonical MS JSON ┴→ ContentIngestionService (T-013: validate → checksum
 *                            dedup → JSONB → deterministic chunks)   [NEVER embeds]
 * parser QP draft ┐
 * parser MS draft ┴→ GlmOcrDraftMapper → PastPaperIngestionService (T-011:
 *                    ExamPaper/Question/QuestionVersion/QuestionPart/MarkScheme/
 *                    MarkPoint, all SUGGESTED, ingestion-anchor topic)
 * parser reconciliation ─→ preserved verbatim + assembled review findings
 *                    └→ GlmOcrBridgeRecord (V13) — nothing discarded
 * </pre>
 *
 * <p><strong>Determinism:</strong> canonical document ids are the parser's
 * deterministic identities (checksum + engine + version); the bridge never
 * generates one. Reruns resolve to the existing canonical rows (checksum
 * idempotency), the existing assessment rows (bridge record by canonical pair)
 * and report everything as DUPLICATE — never a second copy.</p>
 *
 * <p><strong>Embedding is intentionally NOT part of this bridge.</strong> The
 * pipeline stops after chunking; embedding stays an explicit, separate operation
 * through the existing T-013 {@code DocumentEmbeddingService} (a mixed-model
 * index would be inconsistent, and content must land even with zero API keys).</p>
 */
@Service
public class GlmOcrIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GlmOcrIngestionService.class);

    /** house pattern: the app context exposes no ObjectMapper bean (see
     *  ContentDocumentController/ChunkVectorRepository) */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContentIngestionService contentIngestion;
    private final PastPaperIngestionService pastPaperIngestion;
    private final GlmOcrDraftMapper mapper;
    private final GlmOcrBridgeRecordRepository bridgeRecords;
    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final MarkPointRepository markPoints;

    public GlmOcrIngestionService(ContentIngestionService contentIngestion,
                                   PastPaperIngestionService pastPaperIngestion,
                                   GlmOcrDraftMapper mapper,
                                   GlmOcrBridgeRecordRepository bridgeRecords,
                                   ExamPaperRepository examPapers,
                                   QuestionVersionRepository questionVersions,
                                   MarkSchemeRepository markSchemes,
                                   MarkPointRepository markPoints) {
        this.contentIngestion = contentIngestion;
        this.pastPaperIngestion = pastPaperIngestion;
        this.mapper = mapper;
        this.bridgeRecords = bridgeRecords;
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.markPoints = markPoints;
    }

    /**
     * Ingests one verified QP/MS pair. Safe to re-run: the second run reports
     * DUPLICATE for both documents and the paper and creates no new rows.
     *
     * @param pair      the parser outputs for one QP/MS pair (exact production contract)
     * @param ingestedBy authenticated operator (teacher/admin) or null for ops-CLI runs
     */
    @Transactional
    public PairResult ingestPair(GlmOcrPairRequest pair, UUID ingestedBy) {
        validateBundle(pair);

        // Step 1 — canonical documents through the EXISTING T-013 path.
        // Idempotent by source checksum: a second call reports the existing row.
        ContentIngestionService.IngestionResult qpDoc =
                contentIngestion.ingest(pair.qpCanonical(), pair.qpCanonicalJson(),
                        Document.Kind.QUESTION_PAPER, ingestedBy);
        ContentIngestionService.IngestionResult msDoc =
                contentIngestion.ingest(pair.msCanonical(), pair.msCanonicalJson(),
                        Document.Kind.MARK_SCHEME, ingestedBy);

        // Step 2 — rerun spine: an already-bridged pair resolves to its record.
        var existing = bridgeRecords.findByQpDocumentIdAndMsDocumentId(
                qpDoc.documentId(), msDoc.documentId());
        if (existing.isPresent()) {
            return duplicatePairResult(existing.get(), qpDoc, msDoc);
        }

        // Step 3 — assessment content through the EXISTING T-011 path (all SUGGESTED).
        PastPaperDraftDto t011Draft = mapper.toPastPaperDraft(pair.qpDraft(), pair.msDraft());
        PastPaperIngestionService.IngestionSummary created =
                pastPaperIngestion.ingest(t011Draft, ingestedBy);

        // Step 4 — the bridge record: verbatim drafts + reconciliation + findings.
        List<ReviewFinding> findings = mapper.assembleReviewFindings(
                pair.qpDraft(), pair.msDraft(), pair.reconciliation());
        String reconciliationStatus = pair.reconciliation().reviewRequired()
                ? "REVIEW_REQUIRED" : "OK";
        GlmOcrBridgeRecord record;
        try {
            record = new GlmOcrBridgeRecord(
                    created.paperId(),
                    qpDoc.documentId(), msDoc.documentId(),
                    qpDoc.id(), msDoc.id(),
                    pair.qpCanonical().source().checksum(),
                    pair.msCanonical().source().checksum(),
                    GlmOcrDraftMapper.BRIDGE_METHOD,
                    reconciliationStatus,
                    JSON.writeValueAsString(findings),
                    JSON.writeValueAsString(pair.qpDraft()),
                    JSON.writeValueAsString(pair.msDraft()),
                    JSON.writeValueAsString(pair.reconciliation()),
                    ingestedBy);
            bridgeRecords.save(record);
        } catch (Exception e) {
            throw new IllegalStateException("bridge record serialization failed", e);
        }

        ExamPaper paper = examPapers.findById(created.paperId()).orElse(null);
        log.info("glm-ocr bridge: paper {} ({} questions, {} parts, {} mark points), "
                        + "reconciliation {}, findings {} — embedding intentionally skipped",
                created.paperId(), created.questions(), created.parts(),
                created.markPoints(), reconciliationStatus, findings.size());

        return new PairResult(
                DocumentStatus.of(qpDoc), DocumentStatus.of(msDoc),
                new PaperStatus(false, created.paperId(), paper == null ? null : paper.title()),
                created.questions(), created.parts(),
                markSchemes.findByPaperId(created.paperId()).size(),
                created.markPoints(),
                qpDoc.chunks(), msDoc.chunks(),
                new ReconciliationStatus(reconciliationStatus,
                        pair.reconciliation().mismatchCount(),
                        pair.reconciliation().paperTotalConflict(),
                        pair.reconciliation().qpPaperTotal(),
                        pair.reconciliation().msPaperTotal()),
                findings,
                true); // embedding skipped — explicit T-013 operation remains available
    }

    /**
     * Review surface: the persisted findings for one imported paper. The
     * {@link Optional} distinguishes a MISSING bridge record (empty — the
     * caller decides, e.g. HTTP 404) from an existing record whose findings
     * list is legitimately empty (present, {@code []}): a clean pair (no
     * conflicts, no warnings) has zero findings and that is NOT "not found".
     */
    @Transactional(readOnly = true)
    public Optional<List<ReviewFinding>> reviewFindingsForPaper(UUID paperId) {
        return bridgeRecords.findByPaperId(paperId)
                .map(r -> deserialize(r.reviewFindings()));
    }

    /**
     * Fail-loud bundle checks (never guess): the drafts must reference the very
     * canonical documents supplied, and the reconciliation must be ABOUT this
     * pair (its paper totals must match the drafts it claims to reconcile).
     */
    private static void validateBundle(GlmOcrPairRequest pair) {
        Objects.requireNonNull(pair.qpCanonical(), "qpCanonical is required");
        Objects.requireNonNull(pair.msCanonical(), "msCanonical is required");
        Objects.requireNonNull(pair.qpDraft(), "qpDraft is required");
        Objects.requireNonNull(pair.msDraft(), "msDraft is required");
        Objects.requireNonNull(pair.reconciliation(), "reconciliation is required");

        String qpDocId = pair.qpCanonical().documentId();
        String msDocId = pair.msCanonical().documentId();
        String qpClaimed = pair.qpDraft().paper() == null
                ? null : pair.qpDraft().paper().canonicalDocumentId();
        String msClaimed = pair.msDraft().paper() == null
                ? null : pair.msDraft().paper().canonicalDocumentId();
        if (qpClaimed == null || !qpClaimed.equals(qpDocId)) {
            throw new ConflictException("qp draft claims canonical document "
                    + qpClaimed + " but the supplied QP canonical document is " + qpDocId
                    + " — the bundle mixes documents from different sources");
        }
        if (msClaimed == null || !msClaimed.equals(msDocId)) {
            throw new ConflictException("ms draft claims canonical document "
                    + msClaimed + " but the supplied MS canonical document is " + msDocId
                    + " — the bundle mixes documents from different sources");
        }
        if (!Objects.equals(pair.reconciliation().qpPaperTotal(), pair.qpDraft().paperTotal())) {
            throw new ConflictException("reconciliation qpPaperTotal ("
                    + pair.reconciliation().qpPaperTotal()
                    + ") does not match the QP draft paperTotal ("
                    + pair.qpDraft().paperTotal() + ") — is this reconciliation for this pair?");
        }
        if (!Objects.equals(pair.reconciliation().msPaperTotal(), pair.msDraft().paperTotal())) {
            throw new ConflictException("reconciliation msPaperTotal ("
                    + pair.reconciliation().msPaperTotal()
                    + ") does not match the MS draft paperTotal ("
                    + pair.msDraft().paperTotal() + ") — is this reconciliation for this pair?");
        }
    }

    /** rerun: report the state as imported (from the record), create nothing */
    private PairResult duplicatePairResult(GlmOcrBridgeRecord record,
                                           ContentIngestionService.IngestionResult qpDoc,
                                           ContentIngestionService.IngestionResult msDoc) {
        UUID paperId = record.paperId();
        List<QuestionVersion> versions = questionVersions.findByPaperId(paperId);
        int parts = versions.stream().mapToInt(v -> v.parts().size()).sum();
        List<MarkScheme> schemes = markSchemes.findByPaperId(paperId);
        int points = schemes.stream()
                .mapToInt(s -> markPoints.findByMarkSchemeIdOrderByOrdering(s.id()).size())
                .sum();
        ExamPaper paper = examPapers.findById(paperId).orElse(null);
        List<ReviewFinding> findings = deserialize(record.reviewFindings());
        GlmOcrReconciliationDto imported = deserializeReconciliation(record.reconciliation());

        log.info("glm-ocr bridge rerun: pair {}/{} already ingested as paper {} — no new rows",
                record.qpDocumentId(), record.msDocumentId(), paperId);

        return new PairResult(
                DocumentStatus.of(qpDoc), DocumentStatus.of(msDoc),
                new PaperStatus(true, paperId, paper == null ? null : paper.title()),
                versions.size(), parts, schemes.size(), points,
                qpDoc.chunks(), msDoc.chunks(),
                new ReconciliationStatus(record.reconciliationStatus(),
                        imported.mismatchCount(),
                        imported.paperTotalConflict(),
                        imported.qpPaperTotal(),
                        imported.msPaperTotal()),
                findings,
                true); // embedding intentionally skipped on reruns too
    }

    private List<ReviewFinding> deserialize(String stored) {
        try {
            return JSON.readValue(stored,
                    JSON.getTypeFactory().constructCollectionType(List.class, ReviewFinding.class));
        } catch (Exception e) {
            throw new IllegalStateException("stored review findings are unreadable", e);
        }
    }

    private GlmOcrReconciliationDto deserializeReconciliation(String stored) {
        try {
            return JSON.readValue(stored, GlmOcrReconciliationDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("stored reconciliation is unreadable", e);
        }
    }

    /** One QP/MS pair exactly as the parser produced it (the production contract). */
    public record GlmOcrPairRequest(
            CanonicalDocumentDto qpCanonical,
            String qpCanonicalJson,
            CanonicalDocumentDto msCanonical,
            String msCanonicalJson,
            GlmOcrPaperDraftDto qpDraft,
            GlmOcrMarkSchemeDraftDto msDraft,
            GlmOcrReconciliationDto reconciliation) {
    }

    /** INGESTED (new this run) or DUPLICATE (already present — idempotent rerun). */
    public record DocumentStatus(boolean duplicate, String documentId, UUID rowId, int chunks) {
        static DocumentStatus of(ContentIngestionService.IngestionResult r) {
            return new DocumentStatus(r.duplicate(), r.documentId(), r.id(), r.chunks());
        }
    }

    public record PaperStatus(boolean duplicate, UUID paperId, String title) {
    }

    /**
     * Parser reconciliation, relayed verbatim: status OK/REVIEW_REQUIRED with the
     * conflict evidence (October Q18 marks conflict, 1A 80-vs-120) — never merged.
     */
    public record ReconciliationStatus(String status, int mismatchCount,
                                        boolean paperTotalConflict, Integer qpPaperTotal,
                                        Integer msPaperTotal) {
    }

    /**
     * Structured result of one controlled pair ingestion. embeddingSkipped is
     * always true by design — embedding is a separate explicit T-013 operation.
     */
    public record PairResult(
            DocumentStatus qpDocument,
            DocumentStatus msDocument,
            PaperStatus examPaper,
            int questions,
            int parts,
            int markSchemes,
            int markPoints,
            int qpChunks,
            int msChunks,
            ReconciliationStatus reconciliation,
            List<ReviewFinding> reviewFindings,
            boolean embeddingSkipped) {
    }
}
