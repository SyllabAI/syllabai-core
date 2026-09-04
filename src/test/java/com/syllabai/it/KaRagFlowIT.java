package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentEmbeddingService;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.research.TelemetryEventRepository;
import com.syllabai.teacher.CurriculumReviewService;
import com.syllabai.teacher.ingestion.CurriculumDraftDto;
import com.syllabai.teacher.ingestion.CurriculumIngestionService;
import com.syllabai.tutor.ContextAssembler;
import com.syllabai.tutor.KaRagService;
import com.syllabai.tutor.TutorGenerator;
import com.syllabai.tutor.dto.TutorAnswerView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (T-024): the KA-RAG pipeline end-to-end against a real
 * pgvector Postgres — real-corpus fixtures on both retrieval sides (the
 * 4CH0/1C Jan 2012 mark scheme canonical document AND the IAL Chemistry 2018
 * spec curriculum draft), deterministic fake embeddings (no network), and a
 * recording generator stub so the orchestration, fusion, citations and
 * telemetry are exercised without a live LLM. Grounded generation itself is
 * covered by GroundedTutorGeneratorTest against a fake provider.
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KaRagFlowIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** deterministic bag-of-words hashing — search works offline, no API keys */
    @TestConfiguration
    static class KaRagTestConfig {

        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new HashingEmbeddingProvider();
        }

        /** records invocations; the refusal path must never reach it */
        @Bean
        @Primary
        RecordingGenerator recordingGenerator() {
            return new RecordingGenerator();
        }
    }

    static final class RecordingGenerator implements TutorGenerator {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public GeneratedAnswer generate(String query, ContextAssembler.TutorContext context) {
            calls.incrementAndGet();
            return new GeneratedAnswer(
                    "Grounded answer with citations [1].", "stub-model", "stub");
        }
    }

    @Autowired
    private ContentIngestionService contentIngestion;
    @Autowired
    private DocumentEmbeddingService embedding;
    @Autowired
    private CurriculumIngestionService curriculumIngestion;
    @Autowired
    private CurriculumReviewService review;
    @Autowired
    private KaRagService kaRag;
    @Autowired
    private AuthService authService;
    @Autowired
    private TelemetryEventRepository telemetry;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingGenerator generator;

    private UUID learnerId;

    private void seedCorpus() throws Exception {
        if (learnerId != null) {
            return;   // seed once per class (shared container)
        }
        learnerId = authService.register(new RegisterRequest(
                "karag-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();

        // document side: the real 4CH0/1C Jan 2012 mark scheme, chunked + embedded
        String raw = Files.readString(
                Path.of("src/test/resources/fixtures/canonical-ms-4ch0-1c-jan2012.json"));
        CanonicalDocumentDto dto = JSON.readValue(raw, CanonicalDocumentDto.class);
        var result = contentIngestion.ingest(dto, raw, Document.Kind.MARK_SCHEME, null);
        embedding.embedDocument(result.id());

        // KG side: the real IAL Chemistry 2018 spec outline, validated by a teacher
        String draftRaw = Files.readString(
                Path.of("src/test/resources/fixtures/curriculum-draft-ial-chem-2018.json"));
        CurriculumDraftDto draft = JSON.readValue(draftRaw, CurriculumDraftDto.class);
        var summary = curriculumIngestion.ingest(draft, null);
        review.nodes(summary.curriculumVersionId(), null).forEach(
                node -> review.validateNode(node.id()));
        review.validateVersion(summary.curriculumVersionId());
    }

    @Test
    @Order(1)
    @DisplayName("grounded ask: hybrid evidence, citations, topic stamping, telemetry, registry")
    void groundedAsk() throws Exception {
        seedCorpus();

        TutorAnswerView answer = kaRag.ask(learnerId, "bonding and structure of molecules");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.answer()).contains("[1]");
        // intent matched the validated spec topic…
        assertThat(answer.topics())
                .extracting(TutorAnswerView.TopicMatch::code)
                .contains("IALCHEM2018-U1-T3");
        // …and the vector side contributed real mark-scheme chunks
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations()).anySatisfy(c ->
                assertThat(c.sourceType()).isIn("MARK_SCHEME", "KNOWLEDGE_NODE"));

        // the generator saw a context with evidence (grounding actually grounded)
        assertThat(generator.calls.get()).isGreaterThanOrEqualTo(1);

        // research record: KA_RAG_COMPLETED landed with full provenance
        var events = telemetry.findByLearnerIdOrderByOccurredAtDesc(learnerId,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(events).isNotEmpty();
        var last = events.get(0);
        assertThat(last.type().name()).isEqualTo("KA_RAG_COMPLETED");
        assertThat(last.payload().get("question")).isEqualTo("bonding and structure of molecules");
        assertThat(((Number) last.payload().get("evidenceCount")).intValue())
                .isEqualTo(answer.evidenceCount());
        assertThat(last.payload().get("refused")).isEqualTo(false);
        assertThat(last.payload().get("promptVersion")).isEqualTo("tutor-grounded/v1");

        // §19 registry: the V12 prompt seed exists for the LLM touchpoint
        Integer prompts = jdbc.queryForObject(
                "select count(*) from prompt_versions where registry_key = 'tutor-grounded'",
                Integer.class);
        assertThat(prompts).isEqualTo(1);
        Integer models = jdbc.queryForObject(
                "select count(*) from model_versions where registry_key = 'ka-rag-pipeline'",
                Integer.class);
        assertThat(models).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("vector-only grounding: mark-scheme chunks cite without any KG topic match")
    void vectorOnlyAsk() throws Exception {
        seedCorpus();
        int callsBefore = generator.calls.get();

        // halogen vocabulary lives in the 4CH0/1C mark-scheme table chunk; no
        // spec topic title contains these tokens → the vector side alone grounds
        TutorAnswerView answer = kaRag.ask(learnerId, "chlorine iodine astatine halogens");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.topics()).isEmpty();   // no intent match on the spec outline
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations())
                .anySatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));
        assertThat(generator.calls.get()).isGreaterThan(callsBefore);
    }

    @Test
    @Order(3)
    @DisplayName("off-curriculum question: deterministic refusal, generator never called for it")
    void refusalAsk() throws Exception {
        seedCorpus();
        int callsBefore = generator.calls.get();

        // NB: tokens chosen to be collision-free against the corpus under the
        // fake hashing embeddings (real Gemini embeddings need no such care)
        TutorAnswerView answer = kaRag.ask(learnerId, "cooking recipes ancient pyramids");

        assertThat(answer.refused()).isTrue();
        assertThat(answer.answer()).contains("can't answer that");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.topics()).isEmpty();
        // the refusal is deterministic — no generation was attempted
        assertThat(generator.calls.get()).isEqualTo(callsBefore);

        var events = telemetry.findByLearnerIdOrderByOccurredAtDesc(learnerId,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(events.get(0).payload().get("refused")).isEqualTo(true);
    }

    // ── deterministic fake embeddings (shared shape with ContentPipelineIT) ──

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
