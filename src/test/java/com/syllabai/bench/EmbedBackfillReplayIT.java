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
                null, null, out, "fake-embed", 768, 0, "IT", "2026-09-17", null);

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

        // SHA256SUMS verifies against the actual files (no query pass here — goldDir is null)
        List<String> sums = Files.readAllLines(out.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(sums).hasSize(2); // chunks + manifest (query file absent without a gold dir)
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
                null, null, out, "fake-embed", 768, 0, "IT", "2026-09-17", null);

        assertThat(result.chunksEmbedded()).isZero();
        assertThat(result.chunksStoredTotal()).isEqualTo(3);
        String after = Files.readString(out.resolve("embeddings_chunks.jsonl"), StandardCharsets.UTF_8);
        assertThat(after).isEqualTo(before);
        Integer pending = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is null", Integer.class);
        assertThat(pending).isZero();
    }

    @Test
    @Order(4)
    @DisplayName("quota hard-stop: clean INCOMPLETE artifact, then preload resumes without re-embedding")
    void quotaStopsCleanlyThenPreloadResumes() throws Exception {
        // two fresh pending chunks (distinct content, same document)
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 3, ?, ?::jsonb, 16, now())
                """, UUID.nameUUIDFromBytes("it-embed-chunk-3".getBytes()), DOC_ROW,
                CHUNK_CONTENT_1 + " (variant)", "{}");
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 4, ?, ?::jsonb, 16, now())
                """, UUID.nameUUIDFromBytes("it-embed-chunk-4".getBytes()), DOC_ROW,
                CHUNK_CONTENT_1 + " (variant two)", "{}");

        Path partial = Path.of("target/embed-backfill-it-partial");
        wipe(partial);
        EmbedBackfill.BackfillResult stopped = EmbedBackfill.run(jdbc,
                new OneShotThenQuotaProvider(), null, null, partial,
                "fake-embed", 768, 0, "IT", "2026-09-17", null);
        assertThat(stopped.status()).isEqualTo("INCOMPLETE");
        assertThat(stopped.chunksEmbedded()).isEqualTo(1);
        assertThat(stopped.pendingAfter()).isEqualTo(1);
        // the artifact IS dumped despite the hard stop (preload checkpoint)
        assertThat(partial.resolve("embeddings_chunks.jsonl")).exists();
        List<String> sums = Files.readAllLines(partial.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(sums).hasSize(2); // chunks + manifest (no query pass — goldDir null)

        // resume: preload the partial artifact, normal fake finishes the remainder
        Path resumedOut = Path.of("target/embed-backfill-it-resumed");
        wipe(resumedOut);
        EmbedBackfill.BackfillResult resumed = EmbedBackfill.run(jdbc,
                new FakeEmbeddingProvider(), null, null, resumedOut,
                "fake-embed", 768, 0, "IT", "2026-09-17", partial);
        assertThat(resumed.status()).isEqualTo("COMPLETE");
        assertThat(resumed.chunksEmbedded()).isEqualTo(1); // only the remainder was embedded
        assertThat(resumed.chunksStoredTotal()).isEqualTo(5);
        Integer pending = jdbc.queryForObject(
                "select count(*) from document_chunks where embedding is null", Integer.class);
        assertThat(pending).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("preload with a real snapshot: partial applies (requireComplete=false), replay stays fail-closed")
    void preloadWithSnapshotAppliesPartialButReplayStaysFailClosed() throws Exception {
        // regression pin for run 35229113407: the shared helper asserted full
        // snapshot coverage and rejected the partial preload checkpoint. The
        // snapshot-bearing path (production always passes one) was never IT-
        // covered because the earlier resume test passed snapshot = null.
        Path snapDir = Path.of("target/embed-it-snap");
        wipe(snapDir);
        Files.createDirectories(snapDir);
        String snapDoc = "it-embed-snap-doc";
        String ref0 = snapDoc + ":0";
        String ref1 = snapDoc + ":1";
        String content0 = "Snap chunk zero for preload regression.";
        String content1 = "Snap chunk one for preload regression.";

        List<Map<String, Object>> snapChunks = List.of(
                Map.of("chunk_ref", ref0, "content", content0, "paper_state", "UNKNOWN",
                        "kind", "MARK_SCHEME"),
                Map.of("chunk_ref", ref1, "content", content1, "paper_state", "UNKNOWN",
                        "kind", "MARK_SCHEME"));
        byte[] chunksGz = gzip(JSON.writeValueAsBytes(snapChunks));
        Files.write(snapDir.resolve("chunks.jsonl.gz"), chunksGz);
        Files.write(snapDir.resolve("spec_points.json"), "[]".getBytes(StandardCharsets.UTF_8));
        Files.write(snapDir.resolve("misconceptions.json"), "[]".getBytes(StandardCharsets.UTF_8));
        Files.write(snapDir.resolve("graph_edges.json"), "[]".getBytes(StandardCharsets.UTF_8));
        Files.write(snapDir.resolve("question_anchors.json"), "[]".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> snapManifest = new java.util.LinkedHashMap<>();
        snapManifest.put("snapshot_version", "it-snap-001");
        Map<String, Object> hashes = new java.util.LinkedHashMap<>();
        for (String name : List.of("chunks.jsonl.gz", "spec_points.json", "misconceptions.json",
                "graph_edges.json", "question_anchors.json")) {
            hashes.put(name, sha256Hex(Files.readAllBytes(snapDir.resolve(name))));
        }
        snapManifest.put("files_sha256", hashes);
        snapManifest.put("counts", Map.of("chunks", 2));
        Files.write(snapDir.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(snapManifest));
        BenchSnapshot snapshot = BenchSnapshot.load(snapDir);
        org.assertj.core.api.Assertions.assertThat(snapshot.chunkCount()).isEqualTo(2);

        // seed the bench-shaped rows: chunk ids MUST be the loader's deterministic ids
        UUID docRow = UUID.nameUUIDFromBytes("it-embed-snap-doc-row".getBytes());
        jdbc.update("""
                insert into documents (id, document_id, schema_version, doc_version, kind, source_uri,
                    mime_type, checksum, page_count, element_count, text_element_count,
                    source_engine, source_engine_version, canonical_json, created_at)
                values (?, ?, '1.0', 1, 'MARK_SCHEME', 'it://embed-snap', 'application/pdf',
                    'it-embed-checksum-snap', 1, 2, 2, 'it', '1', ?::jsonb, now())
                """, docRow, snapDoc, "{}");
        for (int i = 0; i < 2; i++) {
            String ref = snapDoc + ":" + i;
            jdbc.update("""
                    insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                        token_estimate, created_at)
                    values (?, ?, ?, ?, ?::jsonb, 16, now())
                    """, UUID.nameUUIDFromBytes(("bench-chunk|" + ref).getBytes(StandardCharsets.UTF_8)),
                    docRow, i, i == 0 ? content0 : content1, "{}");
        }

        // partial artifact: 1 of 2 refs — exactly the production preload shape
        Path partial = Path.of("target/embed-backfill-it-snap-partial");
        wipe(partial);
        Files.createDirectories(partial);
        UUID chunkRow0 = UUID.nameUUIDFromBytes(("bench-chunk|" + ref0).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> artRow = new java.util.LinkedHashMap<>();
        artRow.put("ref", ref0);
        artRow.put("chunk_id", chunkRow0.toString());
        artRow.put("model", "fake-embed");
        artRow.put("v", FakeEmbeddingProvider.vector("doc|" + content0));
        Files.write(partial.resolve("embeddings_chunks.jsonl"),
                (JSON.writeValueAsString(artRow) + "\n").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> artManifest = new java.util.LinkedHashMap<>();
        artManifest.put("model", "fake-embed");
        artManifest.put("dimension", 768);
        artManifest.put("task_types", Map.of("chunks", "RETRIEVAL_DOCUMENT"));
        artManifest.put("pending_after", 1);
        Files.write(partial.resolve("manifest.json"), JSON.writeValueAsBytes(artManifest));

        // 1. replay semantics unchanged: full coverage still asserted (this is
        //    the exact exception that killed run 35229113407)
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Run004A.applyChunkVectors(jdbc, partial, snapshot, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifact carries 1 refs, snapshot has 2 (fail-closed)");

        // 2. preload semantics: the SAME partial artifact applies with requireComplete=false
        int applied = Run004A.applyChunkVectors(jdbc, partial, snapshot, false);
        org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(1);
        // scope to this document — earlier ordered tests already stored fake-embed vectors
        Integer stored = jdbc.queryForObject("""
                select count(*) from document_chunks c
                join documents d on d.id = c.document_row_id
                where d.document_id = ? and c.embedding_model = 'fake-embed'
                  and c.embedding is not null
                """, Integer.class, snapDoc);
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(1);

        // 3. per-row fail-closed checks survive the partial flag: unknown ref still rejects
        Path rogue = Path.of("target/embed-backfill-it-snap-rogue");
        wipe(rogue);
        Files.createDirectories(rogue);
        Map<String, Object> rogueRow = new java.util.LinkedHashMap<>(artRow);
        rogueRow.put("ref", snapDoc + ":9");
        Files.write(rogue.resolve("embeddings_chunks.jsonl"),
                (JSON.writeValueAsString(rogueRow) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(rogue.resolve("manifest.json"), JSON.writeValueAsBytes(artManifest));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Run004A.applyChunkVectors(jdbc, rogue, snapshot, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifact ref not in snapshot");
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void wipe(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** Embeds exactly one chunk, then the free-tier daily quota dies (scripted). */
    static final class OneShotThenQuotaProvider implements EmbeddingProvider {
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

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
            if (calls.incrementAndGet() > 1) {
                throw new EmbedBackfill.EmbeddingRateException(
                        "daily quota exhausted: simulated (RPD)", null);
            }
            return FakeEmbeddingProvider.vector("doc|" + text);
        }

        @Override
        public float[] embedQuery(String text) {
            return FakeEmbeddingProvider.vector("query|" + text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embedDocument).toList();
        }
    }
}
