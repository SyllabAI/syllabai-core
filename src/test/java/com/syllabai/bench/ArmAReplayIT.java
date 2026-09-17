package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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

/**
 * Hermetic end-to-end IT for the arm A replay path (session 94), CI lane only
 * (Docker-gated, same posture as the other *IT classes). The distinguishing
 * {@code bench.fixture=arma} property keeps this class out of Spring's context
 * cache collision with EmbedBackfillReplayIT (identical otherwise) so both get
 * their own container and DB — measured once in CI run 35211338443.
 *
 * <p>Fixture: a tiny seeded corpus
 * (one VALIDATED paper + one SUGGESTED paper), a real {@code EmbedBackfill.run}
 * with an ALIGNED fake provider (query vector == document vector for the same
 * text — passes the production cosine floor deterministically) producing the
 * frozen artifact WITH a query pass, then {@code Run004A.run} applying the
 * artifact fail-closed and driving the PRODUCTION vector serving path.
 *
 * <p>Proves: artifact verification (incl. gold provenance), bit-exact apply
 * through the real storeEmbedding, single-model index rule, the served view
 * surfacing the T-C05 boundary finding (the SUGGESTED-paper duplicate IS
 * served by the production surface), the compliant view filtering it, and the
 * determinism double-pass holding. Asserts set-membership and consistency, not
 * tie-dependent exact ranks (the duplicate-content trick creates an exact
 * cosine tie between the two A-chunks by design).</p>
 */
@SpringBootTest(properties = "bench.fixture=arma")
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArmAReplayIT {

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
        EmbeddingProvider alignedFakeEmbeddingProvider() {
            return new AlignedFakeEmbeddingProvider();
        }
    }

    /**
     * Deterministic, offline, dim-768, and ALIGNED: query and document vectors
     * for the same text are IDENTICAL (arm A's floor 0.15 passes exactly for
     * the identical content; other chunks fall far below). IT-only stand-in.
     */
    static final class AlignedFakeEmbeddingProvider implements EmbeddingProvider {
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
            return vector(text);
        }

        @Override
        public float[] embedQuery(String text) {
            return vector(text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embedDocument).toList();
        }

        static float[] vector(String text) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] h = md.digest(text.getBytes(StandardCharsets.UTF_8));
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

    private static final String DOC_V = "it-arma-doc-validated";
    private static final String DOC_S = "it-arma-doc-suggested";
    private static final UUID DOC_ROW_V = UUID.nameUUIDFromBytes("it-arma-row-v".getBytes());
    private static final UUID DOC_ROW_S = UUID.nameUUIDFromBytes("it-arma-row-s".getBytes());
    private static final String CONTENT_A = "Titration endpoint detection relies on the indicator colour change.";
    private static final String CONTENT_B = "A concordant set of titre results agrees within 0.10 cm3.";
    private static final UUID ROW_V_0 = UUID.nameUUIDFromBytes("it-arma-chunk-v0".getBytes());
    private static final UUID ROW_V_1 = UUID.nameUUIDFromBytes("it-arma-chunk-v1".getBytes());
    private static final UUID ROW_V_2 = UUID.nameUUIDFromBytes("it-arma-chunk-v2".getBytes());
    private static final UUID ROW_S_0 = UUID.nameUUIDFromBytes("it-arma-chunk-s0".getBytes());

    @Test
    @Order(1)
    @DisplayName("seed: VALIDATED + SUGGESTED papers, four chunks, duplicate content across papers")
    void seed() {
        CurriculumVersion cv = curriculumVersions.save(new CurriculumVersion(
                "Edexcel", "IGCSE", "4CH1-ARMA", "IT fixture arm A replay",
                CurriculumVersion.Status.ACTIVE));
        Subject subject = subjects.save(new Subject(cv, "4CH1-ARMA",
                "Chemistry (arm A IT)"));
        examPapers.save(new ExamPaper(subject.id(), "IT arm A validated paper",
                "Edexcel", "IGCSE", null, null, "4CH1-ARMA/V",
                null, DOC_V, ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        examPapers.save(new ExamPaper(subject.id(), "IT arm A suggested paper",
                "Edexcel", "IGCSE", null, null, "4CH1-ARMA/S",
                null, DOC_S, ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        // pin the states the boundary audit depends on (never rely on DB defaults)
        jdbc.update("update exam_papers set validation_state = 'VALIDATED' where paper_code = '4CH1-ARMA/V'");
        jdbc.update("update exam_papers set validation_state = 'SUGGESTED' where paper_code = '4CH1-ARMA/S'");

        jdbc.update("""
                insert into documents (id, document_id, schema_version, doc_version, kind, source_uri,
                    mime_type, checksum, page_count, element_count, text_element_count,
                    source_engine, source_engine_version, canonical_json, created_at)
                values (?, ?, '1.0', 1, 'MARK_SCHEME', 'it://arma', 'application/pdf',
                    ?, 1, 3, 3, 'it', '1', ?::jsonb, now())
                """, DOC_ROW_V, DOC_V, DOC_V, "{}");
        jdbc.update("""
                insert into documents (id, document_id, schema_version, doc_version, kind, source_uri,
                    mime_type, checksum, page_count, element_count, text_element_count,
                    source_engine, source_engine_version, canonical_json, created_at)
                values (?, ?, '1.0', 1, 'MARK_SCHEME', 'it://arma', 'application/pdf',
                    ?, 1, 1, 1, 'it', '1', ?::jsonb, now())
                """, DOC_ROW_S, DOC_S, DOC_S, "{}");
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 0, ?, ?::jsonb, 16, now())
                """, ROW_V_0, DOC_ROW_V, CONTENT_A, "{}");
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 1, ?, ?::jsonb, 16, now())
                """, ROW_V_1, DOC_ROW_V, CONTENT_B, "{}");
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 2, ?, ?::jsonb, 16, now())
                """, ROW_V_2, DOC_ROW_V, CONTENT_A + " (tail)", "{}");
        jdbc.update("""
                insert into document_chunks (id, document_row_id, chunk_index, content, element_ids,
                    token_estimate, created_at)
                values (?, ?, 0, ?, ?::jsonb, 16, now())
                """, ROW_S_0, DOC_ROW_S, CONTENT_A, "{}");
    }

    @Test
    @Order(2)
    @DisplayName("backfill → frozen artifact (with query pass) → Run004A replay: served truth + compliant view")
    void backfillThenReplayArmA() throws Exception {
        // ── gold dir (tiny gold-v1-shaped set) ────────────────────────────────
        Path goldDir = Path.of("target/arma-it-gold");
        wipe(goldDir);
        Files.createDirectories(goldDir);
        String goldJson = JSON.writeValueAsString(List.of(Map.of(
                "id", "it-q-001",
                "class", "it_class",
                "query", CONTENT_A,
                "gold_spec_points", List.of(),
                "gold_evidence", List.of(Map.of(
                        "chunk_ref", DOC_V + ":0",
                        "tier", 2,
                        "rule", "exact content match")),
                "provenance", Map.of("rule", "it fixture", "source", "ArmAReplayIT"))));
        Files.writeString(goldDir.resolve("class_it.json"), goldJson, StandardCharsets.UTF_8);
        String goldSha = EmbedBackfill.sha256(goldDir.resolve("class_it.json"));
        Files.writeString(goldDir.resolve("manifest.json"), JSON.writeValueAsString(Map.of(
                "files_sha256", Map.of("class_it.json", goldSha),
                "counts", Map.of("total", 1))), StandardCharsets.UTF_8);
        // the real gold dir carries a SHA256SUMS (the manifest echo reads it) —
        // the fixture must mirror that shape
        String manifestSha = EmbedBackfill.sha256(goldDir.resolve("manifest.json"));
        Files.writeString(goldDir.resolve("SHA256SUMS"),
                goldSha + "  class_it.json\n" + manifestSha + "  manifest.json\n",
                StandardCharsets.UTF_8);

        // ── real backfill run (fake provider, WITH gold → query pass) ────────
        Path artifactDir = Path.of("target/arma-it-artifact");
        wipe(artifactDir);
        Files.createDirectories(artifactDir);
        EmbedBackfill.BackfillResult backfill = EmbedBackfill.run(jdbc,
                new AlignedFakeEmbeddingProvider(), null, goldDir, artifactDir,
                "fake-embed", 768, 0, "IT", "2026-09-17", null);
        assertThat(backfill.status()).isEqualTo("COMPLETE");
        assertThat(backfill.chunksStoredTotal()).isEqualTo(4);
        assertThat(backfill.queriesEmbedded()).isEqualTo(1);

        // ── arm A replay through Run004A (no snapshot dir: seeded corpus) ────
        Path runOut = Path.of("target/arma-it-run");
        wipe(runOut);
        Run004A.Result result = Run004A.run(jdbc, null, null,
                BenchGold.load(goldDir), goldDir, artifactDir, runOut,
                "IT", "2026-09-17", null);

        assertThat(result.status()).isEqualTo("RECORDED");
        assertThat(result.appliedVectors()).isEqualTo(4);
        assertThat(result.queries()).isEqualTo(1);

        // served view (production truth): the duplicate content A chunks MUST be
        // served (cosine 1.0) — including the SUGGESTED-paper one: the finding.
        @SuppressWarnings("unchecked")
        Map<String, Object> served = (Map<String, Object>) readJson(runOut.resolve("results.json"))
                .get("chunk_axis");
        @SuppressWarnings("unchecked")
        Map<String, Object> servedView = (Map<String, Object>) served.get("served_view");
        @SuppressWarnings("unchecked")
        Map<String, Object> compliantView = (Map<String, Object>) served.get("compliant_view");
        @SuppressWarnings("unchecked")
        List<String> servedRefs = (List<String>) ((Map<String, Object>) ((Map<String, Object>)
                readJson(runOut.resolve("results.json")).get("per_query_chunks")).get("it-q-001")).get("ranked_refs");
        @SuppressWarnings("unchecked")
        List<String> compliantRefs = (List<String>) ((Map<String, Object>) ((Map<String, Object>)
                readJson(runOut.resolve("results.json")).get("per_query_compliant")).get("it-q-001")).get("ranked_refs");

        assertThat(servedRefs).isNotEmpty();
        assertThat(servedRefs).contains(DOC_V + ":0", DOC_S + ":0");
        assertThat(result.violations()).isGreaterThanOrEqualTo(1);
        assertThat(compliantRefs).contains(DOC_V + ":0");
        assertThat(compliantRefs).doesNotContain(DOC_S + ":0");
        assertThat(compliantView.get("corpus_n")).isEqualTo(1); // one VALIDATED paper
        assertThat(servedView.get("corpus_n")).isEqualTo(4);

        // the gold chunk is reachable in the compliant view: recall@1/@2 must be 1.0
        @SuppressWarnings("unchecked")
        Map<String, Object> compliantOverall = (Map<String, Object>) compliantView.get("overall");
        assertThat(compliantOverall.get("recall@5")).isEqualTo(1.0);

        // zero-result honesty: the aligned query always serves something
        assertThat(result.zeroResultQueries()).isZero();

        // evidence files written + checksummed
        assertThat(runOut.resolve("results.json")).exists();
        assertThat(runOut.resolve("RUN_REPORT.md")).exists();
        List<String> sums = Files.readAllLines(runOut.resolve("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(sums).hasSize(2);
        for (String line : sums) {
            String[] parts = line.trim().split("\\s+", 2);
            String recomputed = EmbedBackfill.sha256(runOut.resolve(parts[1].trim()));
            assertThat(recomputed).isEqualTo(parts[0]);
        }

        // resume-safety of the underlying index: re-running the backfill embeds nothing
        EmbedBackfill.BackfillResult second = EmbedBackfill.run(jdbc,
                new AlignedFakeEmbeddingProvider(), null, goldDir, artifactDir,
                "fake-embed", 768, 0, "IT", "2026-09-17", null);
        assertThat(second.chunksEmbedded()).isZero();
    }

    private static void wipe(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static Map<String, Object> readJson(Path path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(path.toFile(),
                mapper.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Object.class));
    }
}
