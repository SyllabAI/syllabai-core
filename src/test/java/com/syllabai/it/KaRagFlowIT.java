package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentEmbeddingService;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.CurriculumVersionRepository;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
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
import java.util.Random;
import java.util.Set;
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
 * spec curriculum draft), deterministic production-geometry fake embeddings
 * (no network; T-C43), and a recording generator stub so the orchestration,
 * fusion, citations and telemetry are exercised without a live LLM. Grounded
 * generation itself is covered by GroundedTutorGeneratorTest against a fake
 * provider.
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

    /** production-geometry fake embeddings — search works offline, no API keys */
    @TestConfiguration
    static class KaRagTestConfig {

        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new ProductionGeometryEmbeddingProvider();
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
    private CurriculumVersionRepository curriculumVersions;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private ExamPaperRepository examPapers;
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

        // T-C07 join path (MANDATORY scope on chunk serving): a document is only
        // served when it joins through exam_papers QP/MS document_id → subjects →
        // curriculum_versions into the RESOLVED owning scope. The 4CH0/1C mark
        // scheme must therefore be linked as a paper of a subject inside the SAME
        // single owning curriculum as the KG side — otherwise the chunk side is
        // invisible (fail-closed) and no MARK_SCHEME citation can ever be cited.
        // (Unlinked documents stay never-served; ContentPipelineIT's negative
        // controls cover that refusal.)
        CurriculumVersion ial = curriculumVersions
                .findById(summary.curriculumVersionId()).orElseThrow();
        Subject msSubject = subjects.save(new Subject(ial, "4CH0", "Chemistry (mark-scheme side)"));
        ExamPaper msPaper = examPapers.save(new ExamPaper(msSubject.id(), "IT paper 4CH0/1C Jan 2012",
                "Edexcel", "IGCSE", null, null, "4CH0/1C",
                null, result.documentId(), ExamPaper.Provenance.PAST_PAPER, "it-fixture", null));
        // T-C20: the serving path is VALIDATED-only — pin the human-validated
        // state this positive control represents (never rely on DB defaults;
        // the SUGGESTED exclusion is ContentPipelineIT Orders 8/9's proof)
        jdbc.update("update exam_papers set validation_state = 'VALIDATED' where id = ?",
                msPaper.id());
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
        assertThat(last.payload().get("promptVersion")).isEqualTo("tutor-grounded/v6");
        // D2 (s145): a grounded answer carries the generator's provider name
        assertThat(last.payload().get("answerProvider")).isEqualTo("stub");
        // s139: the direct service call is single-turn — historyTurns reads 0
        assertThat(((Number) last.payload().get("historyTurns")).intValue()).isZero();

        // §19 registry: V12 seeded v1, V14 the diagnosis-aware v2, V40 the
        // format-aware v3, V41 the working-memory v4, V43 the cross-session
        // memory v5 (all rows present, v5 is live)
        Integer prompts = jdbc.queryForObject(
                "select count(*) from prompt_versions where registry_key = 'tutor-grounded'",
                Integer.class);
        assertThat(prompts).isEqualTo(5);
        Integer models = jdbc.queryForObject(
                "select count(*) from model_versions where registry_key = 'ka-rag-pipeline'",
                Integer.class);
        assertThat(models).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("true hybrid grounding: spec subtopic AND mark-scheme chunk both cite")
    void hybridAsk() throws Exception {
        seedCorpus();
        int callsBefore = generator.calls.get();

        // halogen vocabulary lives on BOTH retrieval sides: spec subtopic
        // U2-T8-C literally names "chlorine, bromine and iodine", and the
        // 4CH0/1C mark-scheme table chunk shares the vocabulary
        TutorAnswerView answer = kaRag.ask(learnerId, "chlorine iodine astatine halogens");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.topics())
                .extracting(TutorAnswerView.TopicMatch::code)
                .contains("IALCHEM2018-U2-T8-C");
        assertThat(answer.citations()).isNotEmpty();
        assertThat(answer.citations())
                .anySatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));
        assertThat(answer.citations())
                .anySatisfy(c -> assertThat(c.sourceType()).isEqualTo("KNOWLEDGE_NODE"));
        assertThat(generator.calls.get()).isGreaterThan(callsBefore);
    }

    @Test
    @Order(3)
    @DisplayName("off-curriculum question: deterministic refusal, generator never called for it")
    void refusalAsk() throws Exception {
        seedCorpus();
        int callsBefore = generator.calls.get();

        // NB: the query carries none of the double's anchored topic vocabulary
        // (so it never touches the semantics axis) and its tokens stay
        // collision-free against the corpus under the hashing remainder leg
        // (real Gemini embeddings need no such care)
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
        // D2 (s145): the deterministic grounding-gate refusal names its provider
        // — the research record distinguishes it from the fail-open guard's
        // "deterministic-paper-refusal" (KaRagServiceTest pins that path)
        assertThat(events.get(0).payload().get("answerProvider"))
                .isEqualTo("deterministic-refusal");
    }

    // ── calibrated fake embeddings (T-C43) — KaRagFlowIT-local ─────────────
    // MIN_COSINE is calibrated to production geometry (0.50 of real Gemini
    // cosine similarity; the T-C42 calibration pack). The bag-of-hashed-words
    // double shared with ContentPipelineIT cannot exercise that floor — a
    // short query vs a long chunk caps at ~0.15–0.4 by construction, so
    // hybridAsk lost its MARK_SCHEME leg the moment the floor moved (CI on
    // PR #44). This double reproduces the envelope the pack measured —
    // same-topic pairs ≥ 0.62, unrelated pairs ≤ 0.44 — deterministically and
    // offline, so the serving path's own tests certify the calibrated floor.
    // The pipeline-level IT keeps the plain hashing double: its assertions
    // never cross the retrieval floor.

    static final class ProductionGeometryEmbeddingProvider implements EmbeddingProvider {

        /** deterministic unit "corpus semantics" axis (fixed seed, offline) */
        private static final float[] SEMANTICS_AXIS = axis();

        /**
         * The topical vocabulary the IT queries share with the fixture
         * corpus. Membership lifts a text onto the semantics axis — the
         * fake-embedding analogue of "semantically on-topic".
         */
        private static final Set<String> TOPIC_VOCABULARY = Set.of(
                "chlorine", "iodine", "astatine", "halogen", "halogens",
                "bonding", "structure", "molecule", "molecules",
                "atom", "atoms", "electron", "electrons", "ion", "ions");

        @Override
        public String model() {
            return "fake-production-geometry";
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

        /**
         * Anchored texts (sharing the fixture's topical vocabulary) are
         * lifted onto the semantics axis: v = 0.9·u + √0.19·r(text), with r
         * the orthogonalized bag-of-hashed-words direction of the text.
         * Same-topic pairs land in [0.62, 1.0] cosine — clear of the 0.50
         * production floor. Unanchored texts return the pure remainder,
         * orthogonal to u, so unrelated pairs cap at √0.19 ≈ 0.44. This is
         * NOT the universal common-component lift ruled out in the T-C43
         * analysis — the axis component is gated on topical vocabulary, so
         * refusalAsk's off-corpus query keeps its sub-floor geometry by
         * construction.
         */
        private float[] embed(String text) {
            float[] remainder = hashing(text);
            for (String word : text.toLowerCase().split("[^a-z0-9]+")) {
                if (TOPIC_VOCABULARY.contains(word)) {
                    return lift(remainder);
                }
            }
            return remainder;
        }

        /** v = 0.9·u + √0.19·r — unit norm, r ⊥ u (Gram–Schmidt, guarded) */
        private static float[] lift(float[] hashing) {
            double projection = 0;
            for (int i = 0; i < hashing.length; i++) {
                projection += hashing[i] * SEMANTICS_AXIS[i];
            }
            float[] r = new float[hashing.length];
            double norm = 0;
            for (int i = 0; i < r.length; i++) {
                r[i] = hashing[i] - (float) (projection * SEMANTICS_AXIS[i]);
                norm += r[i] * r[i];
            }
            if (norm < 1e-12) {
                throw new IllegalStateException(
                        "hashing vector parallel to the semantics axis — reseed the axis");
            }
            float rScale = (float) (Math.sqrt(0.19) / Math.sqrt(norm));
            float[] vector = new float[hashing.length];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (0.9 * SEMANTICS_AXIS[i]) + rScale * r[i];
            }
            return vector;
        }

        /** fixed-seed unit Gaussian axis — deterministic across JVMs */
        private static float[] axis() {
            Random random = new Random(0xC0FFEE42L);
            float[] axis = new float[768];
            double norm = 0;
            for (int i = 0; i < axis.length; i++) {
                axis[i] = (float) random.nextGaussian();
                norm += axis[i] * axis[i];
            }
            float scale = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < axis.length; i++) {
                axis[i] *= scale;
            }
            return axis;
        }

        /** the legacy bag-of-hashed-words embedder — now the remainder leg */
        private static float[] hashing(String text) {
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
