package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.cla.ClaService;
import com.syllabai.cla.ResponseMode;
import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentEmbeddingService;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.LoginRequest;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.learner.TutorTopicEngagement;
import com.syllabai.learner.TutorTopicEngagementRepository;
import com.syllabai.research.TelemetryEventRepository;
import com.syllabai.shared.NotFoundException;
import com.syllabai.tutor.ContextAssembler;
import com.syllabai.tutor.TutorGenerator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CLA step-1 vertical slice over real Postgres/HTTP (V24): the full chain
 * RESOURCE_CONTEXT → KG topic resolution → bounded read-only tools → grounded
 * Tutor stack → citations → interaction evidence → provenance-bearing learner
 * signal — plus the invariant suite the contract demands: fail-closed
 * resolution (subject isolation, validation gate, no existence oracle),
 * read-only tool boundaries (no canonical KG / mastery mutation from chat),
 * LIM provenance (surface + mode + context, never raw text), and honest
 * research telemetry with the tool trace.
 *
 * <p>Generation runs against a recording stub (the GroundedTutorGenerator
 * prompt path is covered by GroundedTutorGeneratorTest); retrieval is real
 * (fake deterministic embeddings over the real canonical mark-scheme
 * fixture, exactly like KaRagFlowIT).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClaFlowIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @TestConfiguration
    static class ClaTestConfig {

        @Bean
        @Primary
        EmbeddingProvider fakeEmbeddingProvider() {
            return new ContentPipelineIT.HashingEmbeddingProvider();
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
        volatile ContextAssembler.TutorContext lastContext;

        @Override
        public GeneratedAnswer generate(String query, ContextAssembler.TutorContext context) {
            calls.incrementAndGet();
            lastContext = context;
            return new GeneratedAnswer(
                    "Grounded answer with citations [1].", "stub-model", "stub");
        }
    }

    @Autowired
    private ContentIngestionService contentIngestion;
    @Autowired
    private DocumentEmbeddingService embedding;
    @Autowired
    private com.syllabai.teacher.ingestion.CurriculumIngestionService curriculumIngestion;
    @Autowired
    private com.syllabai.teacher.CurriculumReviewService review;
    @Autowired
    private ClaService cla;
    @Autowired
    private AuthService authService;
    @Autowired
    private TelemetryEventRepository telemetry;
    @Autowired
    private TutorTopicEngagementRepository engagements;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingGenerator generator;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID learnerId;
    private UUID rootId;
    private UUID topicId;

    private void seed() throws Exception {
        if (learnerId != null) {
            return;
        }
        learnerId = authService.register(new RegisterRequest(
                "cla-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "Cla Learner")).user().id();

        String raw = Files.readString(
                Path.of("src/test/resources/fixtures/canonical-ms-4ch0-1c-jan2012.json"));
        CanonicalDocumentDto dto = JSON.readValue(raw, CanonicalDocumentDto.class);
        var result = contentIngestion.ingest(dto, raw, Document.Kind.MARK_SCHEME, null);
        embedding.embedDocument(result.id());

        String draftRaw = Files.readString(
                Path.of("src/test/resources/fixtures/curriculum-draft-ial-chem-2018.json"));
        com.syllabai.teacher.ingestion.CurriculumDraftDto draft =
                JSON.readValue(draftRaw, com.syllabai.teacher.ingestion.CurriculumDraftDto.class);
        var summary = curriculumIngestion.ingest(draft, null);
        review.nodes(summary.curriculumVersionId(), null).forEach(
                node -> review.validateNode(node.id()));
        review.validateVersion(summary.curriculumVersionId());

        rootId = subjects.findByCode("CHM").orElseThrow().knowledgeNodeId();
        topicId = knowledgeNodes.findByCode("IALCHEM2018-U1-T3").orElseThrow().id();
    }

    @Test
    @Order(1)
    @DisplayName("EXPLAIN: anchored grounded answer, resolved context, tool trace, citations")
    void explainAskGrounded() throws Exception {
        seed();

        ClaAnswerView answer = cla.contextualAsk(learnerId, rootId, topicId,
                ResponseMode.EXPLAIN, "explain bonding and structure");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(answer.citations()).isNotEmpty();

        // the context view is what the SERVER resolved — subject, curriculum version,
        // validation state — and never what the client asserted
        assertThat(answer.context().kind()).isEqualTo("KG_TOPIC");
        assertThat(answer.context().reference()).isEqualTo(topicId);
        assertThat(answer.context().rootId()).isEqualTo(rootId);
        assertThat(answer.context().subjectCode()).isEqualTo("CHM");
        assertThat(answer.context().topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(answer.context().curriculumVersion()).isEqualTo("IAL-CHEM-2018");
        assertThat(answer.context().curriculumBoard()).isEqualTo("Edexcel");
        assertThat(answer.context().validationState()).isEqualTo("VALIDATED");
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.EXPLAIN);

        // deterministic anchors: exactly the resolved topic
        assertThat(answer.topics()).hasSize(1);
        assertThat(answer.topics().get(0).code()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(answer.topics().get(0).matchScore()).isEqualTo(1.0);

        // the fixed read-only tool composition ran and is auditable
        assertThat(answer.tools()).extracting(ClaAnswerView.ToolTraceView::tool)
                .containsExactly("GET_SPECIFICATION_CONTEXT", "GET_RELATED_CONCEPTS",
                        "GET_LEARNER_STATE");

        // the generator received the mode-constrained plan
        assertThat(generator.calls.get()).isGreaterThanOrEqualTo(1);
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA EXPLAIN mode");
    }

    @Test
    @Order(2)
    @DisplayName("SUMMARIZE: mode recorded end to end, plan constrained")
    void summarizeAsk() throws Exception {
        seed();
        ClaAnswerView answer = cla.contextualAsk(learnerId, rootId, topicId,
                ResponseMode.SUMMARIZE, "summarize bonding and structure");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.SUMMARIZE);
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA SUMMARIZE mode");
    }

    @Test
    @Order(3)
    @DisplayName("interaction evidence: provenance-bearing LIM rows, raw text only in research telemetry")
    void learnerEvidenceWithProvenance() throws Exception {
        seed();
        List<TutorTopicEngagement> rows = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, java.time.Instant.now().minusSeconds(3600));
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
            assertThat(row.nodeId()).isEqualTo(topicId);
            assertThat(row.contextKind()).isEqualTo("KG_TOPIC");
            assertThat(row.contextReference()).isEqualTo(topicId);
            assertThat(row.responseMode()).isIn("EXPLAIN", "SUMMARIZE");
            assertThat(row.evidenceCount()).isGreaterThanOrEqualTo(1);
            assertThat(row.refused()).isFalse();
            assertThat(row.answerModel()).isEqualTo("stub-model");
        });
        // classification is deterministic from the learner's own words
        assertThat(rows).anySatisfy(row ->
                assertThat(row.signalType()).isEqualTo("EXPLANATION_REQUEST"));

        // RAW TEXT BOUNDARY: learner memory has no question column at all…
        Integer questionColumns = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'tutor_topic_engagements'
                  and column_name in ('question', 'raw_text', 'chat_text', 'content')
                """, Integer.class);
        assertThat(questionColumns).isZero();

        // …while the research telemetry (audit/history layer) carries it
        var events = telemetry.findByLearnerIdOrderByOccurredAtDesc(learnerId,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(events).isNotEmpty();
        var last = events.get(0);
        assertThat(last.type().name()).isEqualTo("CLA_EXCHANGE_COMPLETED");
        assertThat(last.payload().get("question")).isEqualTo("summarize bonding and structure");
        assertThat(last.payload().get("mode")).isEqualTo("SUMMARIZE");
        assertThat(last.payload().get("contextKind")).isEqualTo("KG_TOPIC");
        assertThat(last.payload().get("provenance")).isEqualTo("cla-contextual/1.0.0");
        // the tool invocation trace is in research telemetry (contract §4.4)
        assertThat(last.payload().get("tools")).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("tool boundary: a CLA exchange does not mutate canonical KG, mastery or misconception state")
    void noCanonicalOrMasteryMutation() throws Exception {
        seed();
        Integer nodeStatusCountBefore = jdbc.queryForObject(
                "select count(*) from knowledge_nodes where validation_status = 'VALIDATED'",
                Integer.class);
        Integer skillsBefore = jdbc.queryForObject(
                "select count(*) from skill_states where learner_id = ?",
                Integer.class, learnerId);
        Integer misconceptionsBefore = jdbc.queryForObject(
                "select count(*) from misconception_states where learner_id = ?",
                Integer.class, learnerId);
        Integer edgesBefore = jdbc.queryForObject(
                "select count(*) from knowledge_edges", Integer.class);

        cla.contextualAsk(learnerId, rootId, topicId, ResponseMode.EXPLAIN,
                "explain bonding and structure again");

        Integer nodeStatusCountAfter = jdbc.queryForObject(
                "select count(*) from knowledge_nodes where validation_status = 'VALIDATED'",
                Integer.class);
        Integer skillsAfter = jdbc.queryForObject(
                "select count(*) from skill_states where learner_id = ?",
                Integer.class, learnerId);
        Integer misconceptionsAfter = jdbc.queryForObject(
                "select count(*) from misconception_states where learner_id = ?",
                Integer.class, learnerId);
        Integer edgesAfter = jdbc.queryForObject(
                "select count(*) from knowledge_edges", Integer.class);

        assertThat(nodeStatusCountAfter).isEqualTo(nodeStatusCountBefore);
        assertThat(edgesAfter).isEqualTo(edgesBefore);
        assertThat(skillsAfter).isEqualTo(skillsBefore);
        assertThat(misconceptionsAfter).isEqualTo(misconceptionsBefore);
    }

    @Test
    @Order(5)
    @DisplayName("fail-closed context: foreign topic, unknown root, and unvalidated content are all 404s")
    void contextResolutionFailsClosed() throws Exception {
        seed();

        // a topic id that exists nowhere in this subject's subtree
        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> cla.contextualAsk(learnerId, rootId, foreign,
                ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(NotFoundException.class);

        // a root that is not a subject root
        assertThatThrownBy(() -> cla.contextualAsk(learnerId, UUID.randomUUID(), topicId,
                ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(NotFoundException.class);

        // unvalidated content is invisible to the CLA — indistinguishable 404,
        // no validation-state oracle
        UUID suggestible = knowledgeNodes.findByCode("IALCHEM2018-U1-T4").orElseThrow().id();
        jdbc.update("update knowledge_nodes set validation_status = 'SUGGESTED' where id = ?",
                suggestible);
        try {
            assertThatThrownBy(() -> cla.contextualAsk(learnerId, rootId, suggestible,
                    ResponseMode.EXPLAIN, "explain"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("validated");
        } finally {
            jdbc.update("update knowledge_nodes set validation_status = 'VALIDATED' where id = ?",
                    suggestible);
        }
    }

    // ── HTTP surface: authorization + fail-safe request handling ────────────────

    private HttpResponse<String> post(String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String body(UUID root, UUID topic, String mode, String question) {
        return """
                {"rootId": "%s", "topicNodeId": "%s", "mode": "%s", "question": "%s"}
                """.formatted(root, topic, mode, question);
    }

    @Test
    @Order(6)
    @DisplayName("authorization: anonymous 401; valid learner token 200 on the same route")
    void httpAuthorization() throws Exception {
        seed();
        String email = "cla-http-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        var registered = authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner"));
        assertThat(registered.user().id()).isNotNull();
        String realToken = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // anonymous → 401 (real filter chain, real socket)
        HttpResponse<String> anonymous = post("/api/v1/learners/me/cla/ask", null,
                body(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(anonymous.statusCode()).isEqualTo(401);

        // forged token → 401
        HttpResponse<String> forged = post("/api/v1/learners/me/cla/ask", "not-a-jwt",
                body(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(forged.statusCode()).isEqualTo(401);

        // control: a valid learner token IS served (the 401s above are not vacuous)
        HttpResponse<String> ok = post("/api/v1/learners/me/cla/ask", realToken,
                body(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("IALCHEM2018-U1-T3");
    }

    @Test
    @Order(7)
    @DisplayName("fail-safe requests: unknown mode and unknown ids fail 400/404, never 500 or an oracle")
    void httpFailSafe() throws Exception {
        seed();
        String email = "cla-http2-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner 2"));
        String token = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // unknown mode → 400 (undeclared modes are rejected, contract §3)
        HttpResponse<String> badMode = post("/api/v1/learners/me/cla/ask", token,
                body(rootId, topicId, "WHISPER", "explain bonding"));
        assertThat(badMode.statusCode()).isEqualTo(400);

        // nonexistent topic → 404, same shape as an unauthorized one (no oracle)
        HttpResponse<String> missing = post("/api/v1/learners/me/cla/ask", token,
                body(rootId, UUID.randomUUID(), "EXPLAIN", "explain bonding"));
        assertThat(missing.statusCode()).isEqualTo(404);

        // nonexistent root → 404
        HttpResponse<String> missingRoot = post("/api/v1/learners/me/cla/ask", token,
                body(UUID.randomUUID(), topicId, "EXPLAIN", "explain bonding"));
        assertThat(missingRoot.statusCode()).isEqualTo(404);

        // blank question → 400 (validation)
        HttpResponse<String> blank = post("/api/v1/learners/me/cla/ask", token,
                body(rootId, topicId, "EXPLAIN", "   "));
        assertThat(blank.statusCode()).isEqualTo(400);
    }
}
