package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.PairResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T-C03 — the durable audit artifact of ONE controlled real-corpus batch run:
 * per-pair outcomes for both passes (first ingestion + in-run idempotency
 * pass), the batch-level invariant verification (all-SUGGESTED, no implicit
 * embedding, no learner servability, conflict preservation, deterministic
 * rerun) and the DB row-count evidence for each stage.
 *
 * <p>This is the artifact a human reviews before ANY wider corpus run is
 * allowed: the batch report answers "what landed, is it safe, is it
 * reversible-by-rerun" without psql access. It is written to
 * {@code batch-audit-report.json} in the batch root by the ops CLI and by the
 * integration test (uploaded from CI as a workflow artifact).</p>
 */
public record GlmOcrBatchAuditReport(
        String bridge,
        String batchRoot,
        int maxPairs,
        int pairCount,
        Instant executedAt,
        UUID operator,
        List<PairAudit> pairs,
        List<InvariantResult> invariants,
        RowCounts before,
        RowCounts afterFirstPass,
        RowCounts afterIdempotencyPass,
        boolean allInvariantsPassed) {

    /** house pattern: the app context exposes no ObjectMapper bean */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (IOException e) {
            throw new UncheckedIOException("batch audit serialization failed", e);
        }
    }

    public static GlmOcrBatchAuditReport fromJson(String json) {
        try {
            return JSON.readValue(json, GlmOcrBatchAuditReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException("batch audit deserialization failed", e);
        }
    }

    /** One pair directory, with both passes recorded side by side. */
    public record PairAudit(String pairDir, PairOutcome firstPass, PairOutcome idempotencyPass) {
    }

    /**
     * The outcome of one pair ingestion — the bridge {@link PairResult} in
     * stable, human-readable form (statuses as strings, no nested records).
     */
    public record PairOutcome(
            String qpDocument,
            String msDocument,
            String paper,
            String qpDocumentId,
            String msDocumentId,
            UUID paperId,
            String paperTitle,
            int questions,
            int parts,
            int markSchemes,
            int markPoints,
            int qpChunks,
            int msChunks,
            String reconciliationStatus,
            int mismatchCount,
            boolean paperTotalConflict,
            Integer qpPaperTotal,
            Integer msPaperTotal,
            int findings,
            boolean embeddingSkipped) {

        static PairOutcome from(PairResult r) {
            return new PairOutcome(
                    r.qpDocument().duplicate() ? "DUPLICATE" : "INGESTED",
                    r.msDocument().duplicate() ? "DUPLICATE" : "INGESTED",
                    r.examPaper().duplicate() ? "DUPLICATE" : "INGESTED",
                    r.qpDocument().documentId(), r.msDocument().documentId(),
                    r.examPaper().paperId(), r.examPaper().title(),
                    r.questions(), r.parts(), r.markSchemes(), r.markPoints(),
                    r.qpChunks(), r.msChunks(),
                    r.reconciliation().status(), r.reconciliation().mismatchCount(),
                    r.reconciliation().paperTotalConflict(),
                    r.reconciliation().qpPaperTotal(), r.reconciliation().msPaperTotal(),
                    r.reviewFindings().size(), r.embeddingSkipped());
        }
    }

    /** One verified batch-level safety invariant. */
    public record InvariantResult(String invariant, boolean passed, String detail) {
    }

    /** DB row counts at each stage of the batch — the zero-new-rows evidence. */
    public record RowCounts(
            long documents,
            long chunks,
            long examPapers,
            long questionVersions,
            long markSchemes,
            long markPoints,
            long bridgeRecords) {

        /** true when no row kind changed between two stages */
        public boolean sameAs(RowCounts other) {
            return documents == other.documents && chunks == other.chunks
                    && examPapers == other.examPapers
                    && questionVersions == other.questionVersions
                    && markSchemes == other.markSchemes
                    && markPoints == other.markPoints
                    && bridgeRecords == other.bridgeRecords;
        }
    }

    /** Ops helper: persist the audit artifact next to the batch it describes. */
    public static Path writeArtifact(GlmOcrBatchAuditReport report, Path batchRoot) {
        Path file = batchRoot.resolve("batch-audit-report.json");
        try {
            Files.createDirectories(batchRoot);
            Files.writeString(file, report.toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("batch audit artifact write failed: " + file, e);
        }
        return file;
    }
}
