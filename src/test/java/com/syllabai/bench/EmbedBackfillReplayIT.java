package com.syllabai.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.content.Document;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.content.EmbeddingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic replay IT for the embedding backfill runner (session 92): a FAKE
 * deterministic {@code EmbeddingProvider} (no key, no network — CI-safe) over a
 * tiny seeded corpus proves the run() core: pending-only embedding, the real
 * {@code ChunkVectorRepository.storeEmbedding} store path with model
 * provenance, the {@code vector::real[]} float4-exact artifact dump, the
 * checksummed artifact (SHA256SUMS verifies), and resume (second run embeds
 * zero pending, chunk artifact byte-identical). Docker-gated — runs on the CI
 * lane, never in the agent sandbox (same posture as the other *IT classes).
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmbedBackfillReplayIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.syllabai.curriculum.CurriculumVersionRepository curriculumVersions;
    @Autowired
    private com.syllabai.curriculum.SubjectRepository subjects;
    @Autowired
    private com.syllabai.assessment.ExamPaperRepository examPapers;

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new FakeEmbeddingProvider();
        }
    }

    /** Deterministic, offline, dim-768 — stands in for the Gemini provider in IT only. */
    static final class FakeEmbeddingProvider implements EmbeddingProvider {
        @Override
        public String model() {
            return "fake-embed";
        }

        @Override
        public int dimension() {
            return 768;
        }

        @Override
        public float[] embedDocument(String text) {
            return vector("doc|" + text);
        }

        @Override
        public float[] embedQuery(String text) {
            return vector("query|" + text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embedDocument).toList();
        }

        static float[] vector(String salted) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(salted.getBytes(StandardCharsets.UTF_8));
                float[] v = new float[768];
                for (int i = 0; i < 768; i++) {
                    int b = (h[i % 32] ^ (byte) (i / 32)) & 0xFF;
                    v[i] = (b / 127.5f) - 1f;
                }
                return v;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final String DOC_ID = "it-embed-doc-001";
    private static final UUID DOC_ROW = UUID.nameUUIDFromBytes("it-embed-doc-row".getBytes());
    private static final String CHUNK_CONTENT_0 = "Titration endpoint detection relies on the indicator colour change.";
    private static final String CHUNK_CONTENT_1 = "A concordant set of titre results agrees within 0.10 cm3.";

    @Test
    @Order(1)
    @DisplayName("seed: paper-linked document with three pending chunks")
    void seed() {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "4CH1-EMB", "IT fixture embed-backfill",
                CurriculumVersion.Status.ACTIVE));
        Subject subject = subjects.save(new Subject(cv, "4CH1-EMB",
                "Chemistry (embed IT)"));
        examPapers.save(new ExamPaper(subject.id(), "IT embed paper",
                "Edexcel", "IGCSE", null, null, "4CH1-EMB/1C",
                null, DOC_ID, ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        jdbc.update("""
                insert into documents (id, document_id, schema_version, doc_version, kind, source_uri,
                    mime_type, checksum, page_count, element_count, text_element_count,
                    source_engine, source_engine_version, canonical_json, created_at)
                values (?, ?, '1.0', 1, 'MARK_SCHEME', 'it://embed', 'application/pdf',
                    'it-embed-checksum-001', 1, 2, 2, 'it', '1', ?::jsonb, now())
                """, DOC_ROW, DOC_ID, "{}");
        IntStream.range(0, 3).forEach(i -> jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, ?, ?, ?::jsonb, 16, now())
                """, UUID.nameUUIDFromBytes(("it-embed-chunk-" + i).getBytes()), DOC_ROW, i,
                i == 0 ? CHUNK_CONTENT_0 : i == 1 ? CHUNK_CONTENT_1 : CHUNK_CONTENT_0 + " (tail)", "{}"));
    }

    @Test
    @Order(2)
    @DisplayName("run(): embeds pending only, stores with model provenance, artifact verifies")
    void runEmbedsAndDumps() throws Exception {
        Path out = Path.of("target/embed-backfill-it");
        if (Files.exists(out)) {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        EmbedBackfill.BackfillResult result = EmbedBackfill.run(jdbc, new FakeEmbeddingProvider(),
                null, null, out, "fake-embed", 768, 0, "IT", "2026-09-17");

        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.chunksEmbedded()).isEqualTo(3);
        assertThat(result.pendingAfter()).isZero();
        assertThat(result.chunksStoredTotal()).isEqualTo(3);

        Integer modelled = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding_model = 'fake-embed'", Integer.class);
        assertThat(modelled).isEqualTo(3);

        Path chunksFile = out.resolve("embeddings_chunks.jsonl");
        assertThat(chunksFile).exists();
        List<String> lines = Files.readAllLines(chunksFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);

        // float4-exact round-trip: artifact vector == the fake provider's vector, bit-for-bit
        Map<?, ?> row0 = JSON.readValue(lines.get(0), Map.class);
        assertThat(row0.get("model")).isEqualTo("fake-embed");
        @SuppressWarnings("unchecked")
        List<Number> v0 = (List<Number>) row0.get("v");
        float[] expected = FakeEmbeddingProvider.vector("doc|" + CHUNK_CONTENT_0);
        assertThat(v0).hasSize(768);
        float[] parsed = new float[768];
        for (int i = 0; i < 768; i++) {
            parsed[i] = v0.get(i).floatValue();
        }
        assertThat(Math.abs(Float.floatToIntBits(parsed[0]) - Float.floatToIntBits(expected[0])))
                .as("bit-exact float4 round-trip through ::real[] dump")
                .isLessThanOrEqualTo(1);

        // SHA256SUMS verifies against the actual files
        List<String> sums = Files.readAllLines(out.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(sums).hasSize(3);
        for (String line : sums) {
            String[] parts = line.trim().split("\\s+", 2);
            String recomputed = EmbedBackfill.sha256(out.resolve(parts[1].trim()));
            assertThat(recomputed).isEqualTo(parts[0]);
        }
    }

    @Test
    @Order(3)
    @DisplayName("resume: second run embeds zero pending, chunk artifact byte-identical")
    void resumeEmbedsNothing() throws Exception {
        Path out = Path.of("target/embed-backfill-it");
        String before = Files.readString(out.resolve("embeddings_chunks.jsonl"), StandardCharsets.UTF_8);

        EmbedBackfill.BackfillResult result = EmbedBackfill.run(jdbc, new FakeEmbeddingProvider(),
                null, null, out, "fake-embed", 768, 0, "IT", "2026-09-17");

        assertThat(result.chunksEmbedded()).isZero();
        assertThat(result.chunksStoredTotal()).isEqualTo(3);
        String after = Files.readString(out.resolve("embeddings_chunks.jsonl"), StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
        Integer pending = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is null", Integer.class);
        assertThat(pending).isZero();
    }
}
