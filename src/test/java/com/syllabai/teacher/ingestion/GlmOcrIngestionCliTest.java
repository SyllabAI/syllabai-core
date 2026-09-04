package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.GlmOcrPairRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

/**
 * The controlled ops CLI: one directory of parser outputs → one service call with
 * the verbatim contract → structured result printed. Pinned against a REAL pair.
 */
class GlmOcrIngestionCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr/october-2025-wph11-01");

    private final GlmOcrIngestionService bridge = mock(GlmOcrIngestionService.class);
    private final Environment environment = mock(Environment.class);

    @Test
    @DisplayName("reads the five parser outputs and forwards the exact contract to the bridge")
    void forwardsVerbatimContract(@TempDir Path temp) throws Exception {
        // assemble a pair directory from the real October fixture files
        for (String file : List.of("qp-canonical.json", "ms-canonical.json", "qp-draft.json",
                "ms-draft.json", "reconciliation.json")) {
            Files.copy(FIXTURES.resolve(file), temp.resolve(file));
        }
        when(environment.getRequiredProperty("syllabai.glmocr.pair-dir"))
                .thenReturn(temp.toString());
        when(bridge.ingestPair(any(), isNull())).thenReturn(new GlmOcrIngestionService.PairResult(
                new GlmOcrIngestionService.DocumentStatus(false, "qp-doc", UUID.randomUUID(), 12),
                new GlmOcrIngestionService.DocumentStatus(false, "ms-doc", UUID.randomUUID(), 14),
                new GlmOcrIngestionService.PaperStatus(false, UUID.randomUUID(), "IAL WPH11/01"),
                20, 26, 20, 53, 12, 14,
                new GlmOcrIngestionService.ReconciliationStatus("OK", 0, false, 80, null),
                List.of(), true));

        GlmOcrIngestionCli cli = new GlmOcrIngestionCli(bridge, environment);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed));
        try {
            cli.run();
        } finally {
            System.setOut(originalOut);
        }

        // the five files were forwarded as the exact parser contract
        ArgumentCaptor<GlmOcrPairRequest> captor = ArgumentCaptor.forClass(GlmOcrPairRequest.class);
        verify(bridge).ingestPair(captor.capture(), isNull());
        GlmOcrPairRequest request = captor.getValue();
        assertThat(request.qpCanonical().documentId()).isEqualTo("e8191629-9288-55d0-833a-19be26f274bd");
        assertThat(request.msCanonical().source().checksum())
                .isEqualTo("03e12f0e44bb1f7e37d7df340fa4eb15c869ff4bff07243c3f7b942fd854408f");
        // canonical JSON passed byte-verbatim as read
        assertThat(request.qpCanonicalJson())
                .isEqualTo(Files.readString(temp.resolve("qp-canonical.json")));
        assertThat(request.qpDraft().questions()).hasSize(20);
        assertThat(request.msDraft().paper().paperReference()).isEqualTo("WPH11/01");
        assertThat(request.reconciliation().paperTotalConflict()).isFalse();

        // the structured result is printed for ops scripting
        assertThat(printed.toString()).contains("\"questions\" : 20").contains("\"embeddingSkipped\" : true");
    }

    @Test
    @DisplayName("a missing or non-directory pair-dir fails loud, not with a stack guess")
    void rejectsBadDirectory(@TempDir Path temp) {
        when(environment.getRequiredProperty("syllabai.glmocr.pair-dir"))
                .thenReturn(temp.resolve("nope").toString());
        GlmOcrIngestionCli cli = new GlmOcrIngestionCli(bridge, environment);

        assertThatThrownBy(cli::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a directory");
        verify(bridge, never()).ingestPair(any(), any());
    }
}
