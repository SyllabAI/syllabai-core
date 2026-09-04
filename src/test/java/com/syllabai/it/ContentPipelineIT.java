package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentEmbeddingService;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.content.InvalidDocumentException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Integration test (T-013): the content pipeline against a real pgvector Postgres —
 * canonical ingestion of the real 4CH0/1C Jan 2012 corpus (parser fixtures), verbatim
 * storage, deterministic chunking, idempotent re-ingest, deterministic fake embeddings
 * (no network in CI), cosine search with provenance, and the kind filter.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ContentPipelineIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** Deterministic bag-of-words hashing — search works offline, no API keys. */
    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        EmbeddingProvider fakeEmbeddingProvider() {
            return new HashingEmbeddingProvider();
        }
    }

    @Autowired
    private ContentIngestionService ingestion;
    @Autowired
    private DocumentEmbeddingService embedding;
    @Autowired
    private ContentRetrievalService retrieval;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentChunkRepository chunks;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Fixture(CanonicalDocumentDto dto, String raw) {
    }

    private static Fixture fixture(String name) throws Exception {
        Path path = Path.of("src/test/resources/fixtures/" + name);
        String raw = Files.readString(path);
        return new Fixture(JSON.readValue(raw, CanonicalDocumentDto.class), raw);
    }

    @Test
    @DisplayName("full pipeline: ingest → chunk → embed → cosine search with provenance")
    void fullPipeline() throws Exception {
        Fixture ms = fixture("canonical-ms-4ch0-1c-jan2012.json");

        ContentIngestionService.IngestionResult result =
                ingestion.ingest(ms.dto(), ms.raw(), Document.Kind.MARK_SCHEME, null);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.chunks()).isGreaterThan(0);
        assertThat(result.elements()).isEqualTo(ms.dto().totalElementCount());
        assertThat(result.pages()).isEqualTo(ms.dto().pageCount());

        // content-preserving storage: JSONB round-trips the canonical tree (the
        // SqlTypes.JSON writer normalizes formatting, so compare parsed trees —
        // the source checksum pins the original FILE, §8)
        Document stored = documents.findById(result.id()).orElseThrow();
        assertThat(JSON.readTree(stored.canonicalJson())).isEqualTo(JSON.readTree(ms.raw()));
        assertThat(stored.sourceEngine()).isEqualTo("opendataloader-pdf");
        assertThat(stored.sourceEngineVersion()).isEqualTo("2.5.7");

        // deterministic chunking: element coverage is total, no duplicates
        List<DocumentChunk> storedChunks = chunks
                .findByDocumentRowIdOrderByChunkIndexAsc(result.id());
        assertThat(storedChunks).hasSize(result.chunks());
        assertThat(storedChunks.stream().flatMap(c -> c.elementIds().stream()))
                .doesNotHaveDuplicates();

        // embed → search the Q7(a) halogens mark points
        DocumentEmbeddingService.EmbeddingResult embedded = embedding.embedDocument(result.id());
        assertThat(embedded.embedded()).isEqualTo(result.chunks());
        assertThat(embedded.model()).isEqualTo("fake-hashing");

        List<com.syllabai.content.ChunkHit> hits =
                retrieval.search("chlorine iodine astatine halogens", Document.Kind.MARK_SCHEME, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).content()).containsIgnoringCase("Chlorine");
        assertThat(hits.get(0).elementIds()).isNotEmpty(); // citation provenance survives
        assertThat(hits.get(0).documentId()).isEqualTo(ms.dto().documentId());
        assertThat(hits.get(0).score()).isGreaterThan(0.0);

        // re-embedding is a no-op
        DocumentEmbeddingService.EmbeddingResult again = embedding.embedDocument(result.id());
        assertThat(again.embedded()).isZero();
        assertThat(again.skipped()).isEqualTo(result.chunks());
    }

    @Test
    @DisplayName("re-ingesting the same source is idempotent; kind filter excludes other kinds")
    void idempotentIngestAndKindFilter() throws Exception {
        Fixture qp = fixture("canonical-qp-4ch0-1c-jan2012.json");

        ContentIngestionService.IngestionResult first =
                ingestion.ingest(qp.dto(), qp.raw(), Document.Kind.QUESTION_PAPER, null);
        assertThat(first.duplicate()).isFalse();

        ContentIngestionService.IngestionResult second =
                ingestion.ingest(qp.dto(), qp.raw(), Document.Kind.QUESTION_PAPER, null);
        assertThat(second.duplicate()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(chunks.countByDocumentRowId(first.id()))
                .isEqualTo(first.chunks()); // no duplicate chunks

        embedding.embedDocument(first.id());
        // kind filter: a MARK_SCHEME-scoped search never leaks QUESTION_PAPER chunks
        // (order-independent: vacuously true if no mark scheme is embedded yet)
        retrieval.search("Write your name here", Document.Kind.MARK_SCHEME, 5)
                .forEach(h -> assertThat(h.kind()).isEqualTo("MARK_SCHEME"));
        // unfiltered, the top hit for QP text is the QP document itself
        var open = retrieval.search("Write your name here", null, 5);
        assertThat(open).isNotEmpty();
        assertThat(open.get(0).documentId()).isEqualTo(qp.dto().documentId());
        assertThat(open.get(0).content()).containsIgnoringCase("name");
    }

    @Test
    @DisplayName("a tampered canonical document is rejected with the full invariant list")
    void tamperedDocumentRejected() throws Exception {
        Fixture qp = fixture("canonical-qp-4ch0-1c-jan2012.json");
        CanonicalDocumentDto tampered = new CanonicalDocumentDto(
                qp.dto().documentId(), "0.9", qp.dto().version(), qp.dto().source(),
                qp.dto().pageCount(), qp.dto().pages(), qp.dto().sections(),
                qp.dto().textBlocks(), qp.dto().tables(), qp.dto().figures(),
                qp.dto().equations(), qp.dto().provenance());

        assertThatThrownBy(() -> ingestion.ingest(tampered, qp.raw(), Document.Kind.OTHER, null))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    /**
     * Deterministic offline double: lowercase word → positive hash mod 768, L2-normalized.
     * Real embeddings are Gemini's (see DocumentEmbeddingService); this one only needs
     * to make shared words imply cosine similarity — enough to exercise the pgvector
     * operators end-to-end without network access in CI.
     */
    static final class HashingEmbeddingProvider implements EmbeddingProvider {

        @Override
        public String model() {
            return "fake-hashing";
        }

        @Override
        public int dimension() {
            return 768;
        }

        @Override
        public float[] embedDocument(String text) {
            return embed(text);
        }

        @Override
        public float[] embedQuery(String text) {
            return embed(text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        private float[] embed(String text) {
            float[] vector = new float[768];
            for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
                if (word.isBlank()) {
                    continue;
                }
                int dim = Math.floorMod(word.hashCode(), 768);
                vector[dim] += 1f;
            }
            double norm = 0;
            for (float v : vector) {
                norm += v * v;
            }
            if (norm > 0) {
                float scale = (float) (1.0 / Math.sqrt(norm));
                for (int i = 0; i < vector.length; i++) {
                    vector[i] *= scale;
                }
            } else {
                vector[0] = 1f;
            }
            return vector;
        }
    }
}
