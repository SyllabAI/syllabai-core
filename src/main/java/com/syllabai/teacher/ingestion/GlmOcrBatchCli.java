package com.syllabai.teacher.ingestion;

import com.syllabai.teacher.ingestion.GlmOcrBatchAuditReport.InvariantResult;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Controlled ops CLI for the T-C03 batch (activates ONLY when
 * {@code syllabai.glmocr.batch-dir} is set, so normal boots are unaffected):
 *
 * <pre>
 * java -jar syllabai-core.jar --syllabai.glmocr.batch-dir=/path/to/batch-root \
 *        [--syllabai.glmocr.batch-max-pairs=10]
 * </pre>
 *
 * The batch root holds one sub-directory per QP/MS pair (the five parser
 * outputs each — the same bundle the single-pair CLI consumes). The run:
 * (1) refuses anything beyond the bound (firehose guard, default
 * {@value GlmOcrBatchService#DEFAULT_MAX_PAIRS}); (2) ingests every pair
 * through the unchanged T-C02 bridge in ONE transaction; (3) verifies the
 * batch-level safety invariants against the database; (4) writes
 * {@code batch-audit-report.json} into the batch root — the durable artifact
 * for human review; (5) fails loudly (non-zero exit) if ANY invariant fails.
 *
 * <p>All imported content stays SUGGESTED, no chunk is embedded, nothing
 * serves to learners, conflicts stay review-visible, reruns change nothing.</p>
 */
@Component
@ConditionalOnProperty("syllabai.glmocr.batch-dir")
public class GlmOcrBatchCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GlmOcrBatchCli.class);

    private final GlmOcrBatchService batchService;
    private final Environment environment;

    public GlmOcrBatchCli(GlmOcrBatchService batchService, Environment environment) {
        this.batchService = batchService;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        // resolves --syllabai.glmocr.batch-dir=… command-line args, env vars and
        // application.properties alike (command-line args are NOT system properties)
        Path root = Path.of(environment.getRequiredProperty("syllabai.glmocr.batch-dir"));
        int maxPairs = environment.getProperty("syllabai.glmocr.batch-max-pairs",
                Integer.class, GlmOcrBatchService.DEFAULT_MAX_PAIRS);

        GlmOcrBatchAuditReport report = batchService.runBatch(root, null, maxPairs);

        Path artifact = GlmOcrBatchAuditReport.writeArtifact(report, root);

        // human-readable summary for the operator (the JSON artifact carries the rest)
        log.info("glm-ocr batch audit: {} pair(s), bound {} — {}", report.pairCount(),
                report.maxPairs(), report.allInvariantsPassed()
                        ? "ALL invariants passed" : "INVARIANT FAILURES");
        for (InvariantResult invariant : report.invariants()) {
            log.info("  [{}] {}: {}", invariant.passed() ? "PASS" : "FAIL",
                    invariant.invariant(), invariant.detail());
        }
        for (var pair : report.pairs()) {
            log.info("  pair {}: paper {} ({}), {} questions / {} parts / {} points,"
                            + " reconciliation {} ({} findings)",
                    pair.pairDir(), pair.firstPass().paperTitle(),
                    pair.firstPass().paper(), pair.firstPass().questions(),
                    pair.firstPass().parts(), pair.firstPass().markPoints(),
                    pair.firstPass().reconciliationStatus(), pair.firstPass().findings());
        }
        System.out.println("batch audit report written to: " + artifact.toAbsolutePath());

        if (!report.allInvariantsPassed()) {
            // fail loudly: an unsafe batch must never look like a success
            throw new IllegalStateException("glm-ocr batch audit FAILED — see "
                    + artifact.toAbsolutePath());
        }
    }
}
