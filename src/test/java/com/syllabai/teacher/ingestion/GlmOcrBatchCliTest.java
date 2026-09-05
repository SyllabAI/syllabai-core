package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.InvariantResult;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.PairAudit;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.PairOutcome;
import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.RowCounts;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

/**
 * The controlled ops CLI: batch root → one service call → the audit artifact
 * written into the batch root → loud failure when any invariant fails.
 */
class GlmOcrBatchCliTest {

    private final GlmOcrBatchService batchService = mock(GlmOcrBatchService.class);
    private final Environment environment = mock(Environment.class);

    @Test
    @DisplayName("runs the bounded batch and writes batch-audit-report.json into the batch root")
    void writesAuditArtifact(@TempDir Path root) throws Exception {
        when(environment.getRequiredProperty("syllabai.glmocr.batch-dir"))
                .thenReturn(root.toString());
        when(environment.getProperty("syllabai.glmocr.batch-max-pairs",
                Integer.class, GlmOcrBatchService.DEFAULT_MAX_PAIRS)).thenReturn(5);
        when(batchService.runBatch(root.toAbsolutePath().normalize(), null, 5))
                .thenReturn(passingReport(root));

        GlmOcrBatchCli cli = new GlmOcrBatchCli(batchService, environment);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, "UTF-8"));
        try {
            cli.run();
        } finally {
            System.setOut(originalOut);
        }

        Path artifact = root.resolve("batch-audit-report.json");
        assertThat(artifact).isRegularFile();

        // the artifact IS the report — round-trips through JSON
        GlmOcrBatchAuditReport written = GlmOcrBatchAuditReport.fromJson(
                Files.readString(artifact));
        assertThat(written.pairCount()).isEqualTo(1);
        assertThat(written.allInvariantsPassed()).isTrue();

        // and the operator got the location on stdout
        assertThat(printed.toString()).contains(artifact.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("an invariant failure exits non-zero — an unsafe batch never looks successful")
    void failsLoudOnInvariantFailure(@TempDir Path root) {
        when(environment.getRequiredProperty("syllabai.glmocr.batch-dir"))
                .thenReturn(root.toString());
        when(environment.getProperty("syllabai.glmocr.batch-max-pairs",
                Integer.class, GlmOcrBatchService.DEFAULT_MAX_PAIRS))
                .thenReturn(GlmOcrBatchService.DEFAULT_MAX_PAIRS);
        when(batchService.runBatch(any(), isNull(), eq(GlmOcrBatchService.DEFAULT_MAX_PAIRS)))
                .thenReturn(failingReport(root));

        GlmOcrBatchCli cli = new GlmOcrBatchCli(batchService, environment);

        // the artifact is still written (for review) before the loud failure
        assertThatThrownBy(cli::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch audit FAILED");
        assertThat(root.resolve("batch-audit-report.json")).isRegularFile();
    }

    private static GlmOcrBatchAuditReport passingReport(Path root) {
        return report(root, true);
    }

    private static GlmOcrBatchAuditReport failingReport(Path root) {
        return report(root, false);
    }

    private static GlmOcrBatchAuditReport report(Path root, boolean allPassed) {
        return new GlmOcrBatchAuditReport(
                GlmOcrDraftMapper.BRIDGE_METHOD,
                root.toAbsolutePath().normalize().toString(),
                5, 1, Instant.parse("2026-09-05T18:00:00Z"), null,
                List.of(new PairAudit("june",
                        new PairOutcome("INGESTED", "INGESTED", "INGESTED",
                                "june-qp", "june-ms", UUID.randomUUID(), "June 2025 WPH11/01",
                                20, 20, 20, 51, 38, 14,
                                "OK", 0, false, 80, 80, 0, true),
                        new PairOutcome("DUPLICATE", "DUPLICATE", "DUPLICATE",
                                "june-qp", "june-ms", UUID.randomUUID(), "June 2025 WPH11/01",
                                20, 20, 20, 51, 38, 14,
                                "OK", 0, false, 80, 80, 0, true))),
                List.of(new InvariantResult("all-content-suggested", true, "…"),
                        new InvariantResult("no-implicit-embedding", true, "…"),
                        new InvariantResult("not-learner-servable", true, "…"),
                        new InvariantResult("conflict-preservation", true, "…"),
                        new InvariantResult("deterministic-rerun", allPassed, "…")),
                new RowCounts(0, 0, 0, 0, 0, 0, 0),
                new RowCounts(2, 52, 1, 20, 20, 51, 1),
                new RowCounts(2, 52, 1, 20, 20, 51, 1),
                allPassed);
    }
}
