package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.GlmOcrPairRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Controlled ops CLI for the T-C02 bridge (T-C02 contract: "a small ops/CLI
 * interface"). Activates ONLY when {@code syllabai.glmocr.pair-dir} is set, so
 * normal boots are unaffected:
 *
 * <pre>
 * java -jar syllabai-core.jar --syllabai.glmocr.pair-dir=/path/to/pair-dir
 * </pre>
 *
 * The directory must hold the five parser outputs for ONE pair, exactly as the
 * parser workbench wrote them: {@code qp-canonical.json}, {@code ms-canonical.json},
 * {@code qp-draft.json}, {@code ms-draft.json}, {@code reconciliation.json}.
 * The run prints the structured bridge result and leaves the rest of the
 * application lifecycle untouched (a one-shot operation, then normal startup
 * continues).
 */
@Component
@ConditionalOnProperty("syllabai.glmocr.pair-dir")
public class GlmOcrIngestionCli implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GlmOcrIngestionCli.class);

    private final GlmOcrIngestionService bridge;
    private final ObjectMapper json;
    private final Environment environment;

    public GlmOcrIngestionCli(GlmOcrIngestionService bridge, ObjectMapper json,
                              Environment environment) {
        this.bridge = bridge;
        this.json = json;
        this.environment = environment;
    }

    @Override
    public void run(String... args) throws Exception {
        // resolves --syllabai.glmocr.pair-dir=… command-line args, env vars and
        // application.properties alike (command-line args are NOT system properties)
        Path dir = Path.of(environment.getRequiredProperty("syllabai.glmocr.pair-dir"));
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("syllabai.glmocr.pair-dir is not a directory: " + dir);
        }

        // canonical JSON stays byte-verbatim as read from the parser output
        String qpCanonicalJson = Files.readString(dir.resolve("qp-canonical.json"));
        String msCanonicalJson = Files.readString(dir.resolve("ms-canonical.json"));

        GlmOcrPairRequest request = new GlmOcrPairRequest(
                json.readValue(qpCanonicalJson, CanonicalDocumentDto.class),
                qpCanonicalJson,
                json.readValue(msCanonicalJson, CanonicalDocumentDto.class),
                msCanonicalJson,
                json.readValue(dir.resolve("qp-draft.json").toFile(), GlmOcrPaperDraftDto.class),
                json.readValue(dir.resolve("ms-draft.json").toFile(), GlmOcrMarkSchemeDraftDto.class),
                json.readValue(dir.resolve("reconciliation.json").toFile(),
                        GlmOcrReconciliationDto.class));

        GlmOcrIngestionService.PairResult result = bridge.ingestPair(request, null);

        log.info("glm-ocr pair {} ingested: qpDocument={} ({} chunks), msDocument={} ({} chunks), "
                        + "paper={} {} questions/parts/points={}/{}/{}, reconciliation={}, "
                        + "findings={}, embeddingSkipped={}",
                dir.getFileName(),
                result.qpDocument().duplicate() ? "DUPLICATE" : "INGESTED",
                result.qpDocument().chunks(),
                result.msDocument().duplicate() ? "DUPLICATE" : "INGESTED",
                result.msDocument().chunks(),
                result.examPaper().duplicate() ? "DUPLICATE" : "INGESTED",
                result.examPaper().paperId(),
                result.questions(), result.parts(), result.markPoints(),
                result.reconciliation().status(),
                result.reviewFindings().size(),
                result.embeddingSkipped());

        // structured result on stdout for ops scripting
        System.out.println(json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result));
    }
}
