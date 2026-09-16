package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.cla.AttemptRequiredException;
import com.syllabai.cla.ClaService;
import com.syllabai.cla.ResponseMode;
import com.syllabai.cla.ResourceContext;
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
 * CLA step-1 + step-2 vertical slice over real Postgres/HTTP (V24): the full
 * chain RESOURCE_CONTEXT → KG topic / question resolution → bounded read-only
 * tools → grounded Tutor stack → citations → interaction evidence →
 * provenance-bearing learner signal — plus the invariant suite the contract
 * demands, INCLUDING the CI-mandatory answer-leakage negative suite (§7.5):
 *
 * <ul>
 *   <li>fail-closed resolution (subject isolation, validation gates, no
 *       existence oracles) for both context kinds;</li>
 *   <li>read-only tool boundaries (no canonical KG / mastery mutation);</li>
 *   <li>LIM provenance (surface + mode + context, never raw text);</li>
 *   <li>the §7 leakage gate over real attempt state: HINT never carries
 *       mark-scheme evidence (unit-pinned non-vacuously; asserted here
 *       end-to-end), CHECK pre-attempt is a deterministic 409 BEFORE any
 *       generation, and the post-attempt attempt evidence unlocks CHECK.</li>
 * </ul>
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

    /** V6 seed: subject root CHM. */
    private static final UUID SEED_SUBJECT_ROOT =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    /** V6 seed: WCH11-T1.1 (mole calculations) — the seed MCQs' topic. */
    private static final UUID SEED_TOPIC_T1_1 =
            UUID.fromString("20000000-0000-0000-0000-000000000012");
    /** V7 seed: SEED-WCH11-001 (mass of 0.25 mol CaCO3) — servable seed MCQ. */
    private static final UUID SEED_MCQ =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: option C "25.0 g" — the correct answer. */
    private static final UUID SEED_MCQ_CORRECT_OPTION =
            UUID.fromString("41000000-0000-0000-0000-000000000003");

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
    private AssessmentService assessment;
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
    @Autowired
    private com.syllabai.teacher.ingestion.PastPaperIngestionService paperIngestion;
    @Autowired
    private com.syllabai.assessment.QuestionRepository questions;
    @Autowired
    private com.syllabai.assessment.QuestionVersionRepository questionVersions;
    @Autowired
    private com.syllabai.assessment.MarkSchemeRepository markSchemes;
    @Autowired
    private com.syllabai.assessment.QuestionPartRepository questionParts;
    @Autowired
    private com.syllabai.teacher.ContentReviewService contentReview;

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID learnerId;
    private UUID rootId;
    private UUID topicId;
    // QUESTION_PART slice state (seeded once, shared by the part-flow tests)
    private UUID partQuestionId;
    private UUID partAId;
    private UUID partBId;
    private UUID partAnchorNodeId;

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

    private UUID freshLearner() {
        return authService.register(new RegisterRequest(
                "cla-n-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "Cla Learner N")).user().id();
    }

    // ── step 1: the KG_TOPIC vertical slice ─────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("EXPLAIN: anchored grounded answer, resolved context, tool trace, citations")
    void explainAskGrounded() throws Exception {
        seed();

        ClaAnswerView answer = cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                rootId, topicId, null, null, null , ResponseMode.EXPLAIN,
                "explain bonding and structure");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.answer()).contains("[1]");
        assertThat(answer.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(answer.citations()).isNotEmpty();

        // the context view is what the SERVER resolved — subject, curriculum version,
        // validation state — and never what the client asserted
        assertThat(answer.context().kind()).isEqualTo("KG_TOPIC");
        assertThat(answer.context().reference()).isEqualTo(topicId);
        assertThat(answer.context().topicNodeId()).isEqualTo(topicId);
        assertThat(answer.context().rootId()).isEqualTo(rootId);
        assertThat(answer.context().subjectCode()).isEqualTo("CHM");
        assertThat(answer.context().topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(answer.context().curriculumVersion()).isEqualTo("IAL-CHEM-2018");
        assertThat(answer.context().curriculumBoard()).isEqualTo("Edexcel");
        assertThat(answer.context().validationState()).isEqualTo("VALIDATED");
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.EXPLAIN);
        assertThat(answer.context().attempted()).isNull();

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
        ClaAnswerView answer = cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                rootId, topicId, null, null, null , ResponseMode.SUMMARIZE, "summarize bonding and structure");

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
        // self-contained ask: this test instance has its own freshly-registered
        // learner (JUnit per-method instances), so the rows asserted here are
        // exactly the rows THIS ask produces
        ClaAnswerView answer = cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                rootId, topicId, null, null, null , ResponseMode.EXPLAIN, "explain ionic bonding");
        assertThat(answer.refused()).isFalse();

        List<TutorTopicEngagement> rows = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learnerId, java.time.Instant.now().minusSeconds(3600));
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
            assertThat(row.nodeId()).isEqualTo(topicId);
            assertThat(row.contextKind()).isEqualTo("KG_TOPIC");
            assertThat(row.contextReference()).isEqualTo(topicId);
            assertThat(row.responseMode()).isEqualTo("EXPLAIN");
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
        assertThat(last.payload().get("question")).isEqualTo("explain ionic bonding");
        assertThat(last.payload().get("mode")).isEqualTo("EXPLAIN");
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

        cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC, rootId, topicId, null, null, null ,
                ResponseMode.EXPLAIN, "explain bonding and structure again");

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
    @DisplayName("fail-closed context: foreign topic, unknown root, unknown question, unvalidated content are all 404s")
    void contextResolutionFailsClosed() throws Exception {
        seed();

        // a topic id that exists nowhere in this subject's subtree
        UUID foreign = UUID.randomUUID();
        assertThatThrownBy(() -> cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                rootId, foreign, null, null, null , ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(NotFoundException.class);

        // a root that is not a subject root
        assertThatThrownBy(() -> cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                UUID.randomUUID(), topicId, null, null, null , ResponseMode.EXPLAIN, "explain"))
                .isInstanceOf(NotFoundException.class);

        // an unknown question id → 404 (no question existence oracle)
        assertThatThrownBy(() -> cla.contextualAsk(learnerId, ResourceContext.Kind.PAST_PAPER_QUESTION,
                null, null, UUID.randomUUID(), null, null , ResponseMode.HINT, "hint me"))
                .isInstanceOf(NotFoundException.class);

        // unvalidated content is invisible to the CLA — indistinguishable 404,
        // no validation-state oracle
        UUID suggestible = knowledgeNodes.findByCode("IALCHEM2018-U1-T4").orElseThrow().id();
        jdbc.update("update knowledge_nodes set validation_status = 'SUGGESTED' where id = ?",
                suggestible);
        try {
            assertThatThrownBy(() -> cla.contextualAsk(learnerId, ResourceContext.Kind.KG_TOPIC,
                    rootId, suggestible, null, null, null , ResponseMode.EXPLAIN, "explain"))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("validated");
        } finally {
            jdbc.update("update knowledge_nodes set validation_status = 'VALIDATED' where id = ?",
                    suggestible);
        }
    }

    // ── step 2: question contexts + the §7 answer-leakage negative suite ────

    @Test
    @Order(6)
    @DisplayName("§7.5 negative suite: HINT on a question context never carries mark-scheme evidence")
    void hintNeverLeaksMarkScheme() throws Exception {
        seed();
        // the V7 seed MCQ is servable and topic-mapped; the corpus contains the
        // real canonical mark-scheme fixture chunks the vector side can retrieve
        ClaAnswerView answer = cla.contextualAsk(freshLearner(),
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null, SEED_MCQ, null, null ,
                ResponseMode.HINT, "give me the mass of calcium carbonate");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().kind()).isEqualTo("PAST_PAPER_QUESTION");
        assertThat(answer.context().reference()).isEqualTo(SEED_MCQ);
        assertThat(answer.context().topicCode()).isEqualTo("WCH11-T1.1");
        assertThat(answer.context().attempted()).isFalse();
        assertThat(answer.context().questionMarks()).isGreaterThan(0);
        // the V7 seed MCQ is a paper-less (SEED_DEMO) question — the subject
        // resolves by subtree containment of its primary topic; paperCode is
        // honestly null rather than fabricated
        assertThat(answer.context().paperCode()).isNull();

        // THE INVARIANT: no mark-scheme source anywhere in the evidence
        assertThat(answer.citations())
                .noneSatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));
        // the deterministic anchor is the question's primary topic
        assertThat(answer.topics()).extracting(t -> t.code()).containsExactly("WCH11-T1.1");
        // HINT plan carries the leakage constraint
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA HINT mode").contains("no final answers");
    }

    @Test
    @Order(7)
    @DisplayName("§7.5 negative suite: CHECK pre-attempt is a deterministic refusal before any generation")
    void checkPreAttemptRefuses() throws Exception {
        seed();
        UUID learner = freshLearner();
        int generatorCallsBefore = generator.calls.get();
        assertThatThrownBy(() -> cla.contextualAsk(learner,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null, SEED_MCQ, null, null ,
                ResponseMode.CHECK, "check my answer"))
                .isInstanceOf(AttemptRequiredException.class);
        // the refusal happened BEFORE the generator: no LLM call for this ask
        // (lastContext persists from earlier tests in the shared context, so
        // the call counter is the self-contained proof)
        assertThat(generator.calls.get()).isEqualTo(generatorCallsBefore);
    }

    @Test
    @Order(8)
    @DisplayName("§7.3 post-attempt unlock: real attempt evidence enables CHECK full feedback")
    void checkPostAttemptUnlocks() throws Exception {
        seed();
        UUID learner = freshLearner();

        // pre-attempt: the gate refuses
        assertThatThrownBy(() -> cla.contextualAsk(learner,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null, SEED_MCQ, null, null ,
                ResponseMode.CHECK, "check my answer"))
                .isInstanceOf(AttemptRequiredException.class);

        // real attempt evidence through the REAL assessment pipeline (Review Hub substrate)
        assessment.submit(learner, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_CORRECT_OPTION, 20_000L, 4, false, false));

        // post-attempt: the gate unlocks
        ClaAnswerView answer = cla.contextualAsk(learner,
                ResourceContext.Kind.PAST_PAPER_QUESTION, null, null, SEED_MCQ, null, null ,
                ResponseMode.CHECK, "check my answer now");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().attempted()).isTrue();
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.CHECK);
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA CHECK mode").contains("post-attempt");
        // no mark-scheme DOCUMENT chunks even post-attempt (page-level chunks
        // cannot be bound to one question) — feedback grounds on the anchor
        assertThat(answer.citations())
                .noneSatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));

        // the exchange landed in LIM with the question context identity
        List<TutorTopicEngagement> rows = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learner, java.time.Instant.now().minusSeconds(3600));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
            assertThat(row.contextKind()).isEqualTo("PAST_PAPER_QUESTION");
            assertThat(row.contextReference()).isEqualTo(SEED_MCQ);
            assertThat(row.responseMode()).isEqualTo("CHECK");
            assertThat(row.nodeId()).isEqualTo(SEED_TOPIC_T1_1);
        });
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

    private String kgBody(UUID root, UUID topic, String mode, String question) {
        return """
                {"kind": "KG_TOPIC", "rootId": "%s", "topicNodeId": "%s", "mode": "%s", "question": "%s"}
                """.formatted(root, topic, mode, question);
    }

    private String questionBody(UUID questionId, String mode, String question) {
        return """
                {"kind": "PAST_PAPER_QUESTION", "questionId": "%s", "mode": "%s", "question": "%s"}
                """.formatted(questionId, mode, question);
    }

    @Test
    @Order(9)
    @DisplayName("authorization: anonymous 401; valid learner token 200 on the same route")
    void httpAuthorization() throws Exception {
        seed();
        String email = "cla-http-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        var registered = authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner"));
        assertThat(registered.user().id()).isNotNull();
        String realToken = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // anonymous → 401 (real filter chain, real socket)
        HttpResponse<String> anonymous = post("/api/v1/learners/me/cla/ask", null,
                kgBody(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(anonymous.statusCode()).isEqualTo(401);

        // forged token → 401
        HttpResponse<String> forged = post("/api/v1/learners/me/cla/ask", "not-a-jwt",
                kgBody(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(forged.statusCode()).isEqualTo(401);

        // control: a valid learner token IS served (the 401s above are not vacuous)
        HttpResponse<String> ok = post("/api/v1/learners/me/cla/ask", realToken,
                kgBody(rootId, topicId, "EXPLAIN", "explain bonding"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("IALCHEM2018-U1-T3");
    }

    @Test
    @Order(10)
    @DisplayName("fail-safe requests: unknown mode/kind and unknown ids fail 400/404/409, never 500 or an oracle")
    void httpFailSafe() throws Exception {
        seed();
        String email = "cla-http2-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner 2"));
        String token = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // unknown mode → 400 (undeclared modes are rejected, contract §3)
        HttpResponse<String> badMode = post("/api/v1/learners/me/cla/ask", token,
                kgBody(rootId, topicId, "WHISPER", "explain bonding"));
        assertThat(badMode.statusCode()).isEqualTo(400);

        // unsupported context kind (closed enum, not served by this runtime) → 400
        HttpResponse<String> badKind = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "NOTE_SECTION", "mode": "EXPLAIN", "question": "explain"}
                """);
        assertThat(badKind.statusCode()).isEqualTo(400);

        // missing reference for the declared kind → 400
        HttpResponse<String> missingRef = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "KG_TOPIC", "mode": "EXPLAIN", "question": "explain"}
                """);
        assertThat(missingRef.statusCode()).isEqualTo(400);

        // nonexistent topic → 404, same shape as an unauthorized one (no oracle)
        HttpResponse<String> missing = post("/api/v1/learners/me/cla/ask", token,
                kgBody(rootId, UUID.randomUUID(), "EXPLAIN", "explain bonding"));
        assertThat(missing.statusCode()).isEqualTo(404);

        // nonexistent root → 404
        HttpResponse<String> missingRoot = post("/api/v1/learners/me/cla/ask", token,
                kgBody(UUID.randomUUID(), topicId, "EXPLAIN", "explain bonding"));
        assertThat(missingRoot.statusCode()).isEqualTo(404);

        // §7.5 over real HTTP: CHECK pre-attempt → 409 attempt_required
        HttpResponse<String> preAttempt = post("/api/v1/learners/me/cla/ask", token,
                questionBody(SEED_MCQ, "CHECK", "check my answer"));
        assertThat(preAttempt.statusCode()).isEqualTo(409);
        assertThat(preAttempt.body()).contains("attempt_required");

        // blank question → 400 (validation)
        HttpResponse<String> blank = post("/api/v1/learners/me/cla/ask", token,
                kgBody(rootId, topicId, "EXPLAIN", "   "));
        assertThat(blank.statusCode()).isEqualTo(400);
    }
    private String specBody(UUID root, String code, String mode, String question) {
        return """
                {"kind": "SPECIFICATION_POINT", "rootId": "%s", "specCode": "%s", "mode": "%s", "question": "%s"}
                """.formatted(root, code, mode, question);
    }

    @Test
    @Order(11)
    @DisplayName("SPECIFICATION_POINT: the spec-point code resolves server-side; unknown code is a 404")
    void specificationPointFlow() throws Exception {
        seed();
        String email = "cla-http3-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner 3"));
        String token = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // the syllabus-browser anchor: code in, validated context out
        HttpResponse<String> ok = post("/api/v1/learners/me/cla/ask", token,
                specBody(rootId, "IALCHEM2018-U1-T3", "EXPLAIN", "explain this spec point"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"kind\":\"SPECIFICATION_POINT\"");
        assertThat(ok.body()).contains("IALCHEM2018-U1-T3");

        // unknown code → 404 (no oracle); foreign subject's code → 404 too
        HttpResponse<String> unknown = post("/api/v1/learners/me/cla/ask", token,
                specBody(rootId, "IALCHEM2018-U1-T99", "EXPLAIN", "explain"));
        assertThat(unknown.statusCode()).isEqualTo(404);
        HttpResponse<String> foreign = post("/api/v1/learners/me/cla/ask", token,
                specBody(rootId, "WCH11-T1.1", "EXPLAIN", "explain"));
        assertThat(foreign.statusCode()).isEqualTo(404);

        // missing code for the declared kind → 400
        HttpResponse<String> missing = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "SPECIFICATION_POINT", "rootId": "%s", "mode": "EXPLAIN", "question": "explain"}
                """.formatted(rootId));
        assertThat(missing.statusCode()).isEqualTo(400);
    }

    // ── QUESTION_PART: part-level anchor, part-scoped feedback, same gates ──

    private void seedPartQuestion() throws Exception {
        seed();
        if (partAId != null) {
            return;
        }
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        var draft = new com.syllabai.teacher.ingestion.PastPaperDraftDto(
                "1.0",
                new com.syllabai.teacher.ingestion.PastPaperDraftDto.PaperMeta(
                        "Edexcel", "IGCSE", "Chemistry", "Paper 2C",
                        "June 2013-" + suffix, "4CH0/2P-" + suffix, "it-qp-doc", "it-ms-doc"),
                List.of(new com.syllabai.teacher.ingestion.PastPaperDraftDto.QuestionDraft(
                        "qp1", "1", "Question 1 stem", "Explain", 4, "STRUCTURED", 1, 0.6,
                        List.of(
                                new com.syllabai.teacher.ingestion.PastPaperDraftDto.PartDraft(
                                        "a", "Part a prompt: why do molten salts conduct?",
                                        "State", 2, 0.6),
                                new com.syllabai.teacher.ingestion.PastPaperDraftDto.PartDraft(
                                        "b", "Part b prompt: compare with solid behavior.",
                                        "Explain", 2, 0.6)))),
                new com.syllabai.teacher.ingestion.PastPaperDraftDto.MarkSchemeDraft("1",
                        "it-ms-doc",
                        List.of(
                                new com.syllabai.teacher.ingestion.PastPaperDraftDto.MarkPointDraft(
                                        "1-a", 1, "PART A POINT: ions free to move when molten",
                                        2, List.of(), 0.6),
                                new com.syllabai.teacher.ingestion.PastPaperDraftDto.MarkPointDraft(
                                        "1-b", 2, "SIBLING PART B POINT: solid ions fixed in lattice",
                                        2, List.of(), 0.6),
                                new com.syllabai.teacher.ingestion.PastPaperDraftDto.MarkPointDraft(
                                        "1", 3, "QUESTION LEVEL POINT: conclusion consistent",
                                        2, List.of(), 0.6))),
                "it-test-question-part",
                true);
        var summary = paperIngestion.ingest(draft, null);
        com.syllabai.assessment.Question question = questions.findAllByOrderByDifficultyAsc()
                .stream().filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        com.syllabai.assessment.QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        contentReview.validateQuestionVersion(version.id());
        com.syllabai.assessment.MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        contentReview.validateMarkScheme(scheme.id(), scheme.points().stream()
                .map(pt -> new com.syllabai.teacher.ContentReviewService.PointCriteria(
                        pt.id(), List.of(pt.text())))
                .toList());
        // the ingestion anchor KG node (the question's primary topic) must pass
        // the §1.2 curriculum gate — validate it exactly like a teacher would
        review.validateNode(question.primaryTopicNodeId());
        partQuestionId = question.id();
        partAId = version.parts().stream()
                .filter(p -> "a".equals(p.label())).findFirst().orElseThrow().id();
        partBId = version.parts().stream()
                .filter(p -> "b".equals(p.label())).findFirst().orElseThrow().id();
        partAnchorNodeId = question.primaryTopicNodeId();
    }

    @Test
    @Order(12)
    @DisplayName("QUESTION_PART: HINT anchors the PART, serves grounded, and never leaks scheme points")
    void partHintAnchorsPartAndNeverLeaks() throws Exception {
        seedPartQuestion();
        UUID learner = freshLearner();

        ClaAnswerView answer = cla.contextualAsk(learner,
                ResourceContext.Kind.QUESTION_PART, null, null, null, partAId, null ,
                ResponseMode.HINT, "how do I start this part?");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().kind()).isEqualTo("QUESTION_PART");
        assertThat(answer.context().reference()).isEqualTo(partAId);
        // the PART identity is resolved and exposed, not just the question
        assertThat(answer.context().partLabel()).isEqualTo("a");
        assertThat(answer.context().questionStem()).contains("Part a prompt");
        assertThat(answer.context().questionMarks()).isEqualTo(2);
        assertThat(answer.context().attempted()).isFalse();
        // the deterministic anchor is the question's primary topic
        assertThat(answer.topics()).hasSize(1);
        // THE INVARIANT: HINT pre-attempt carries zero mark-scheme evidence —
        // no synthesized scheme points (not attempted) AND no document chunks
        assertThat(answer.citations())
                .noneSatisfy(c -> assertThat(c.sourceType()).isEqualTo("MARK_SCHEME"));
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA HINT mode").contains("anchored question part (a)");
    }

    @Test
    @Order(13)
    @DisplayName("QUESTION_PART: unknown part / unknown root / unservable are indistinguishable 404s")
    void partResolutionFailsClosed() throws Exception {
        seedPartQuestion();

        // unknown part id → 404, no existence oracle
        assertThatThrownBy(() -> cla.contextualAsk(freshLearner(),
                ResourceContext.Kind.QUESTION_PART, null, null, null, UUID.randomUUID(), null ,
                ResponseMode.HINT, "hint me"))
                .isInstanceOf(NotFoundException.class);

        // a root that is not a subject root → 404
        assertThatThrownBy(() -> cla.contextualAsk(freshLearner(),
                ResourceContext.Kind.QUESTION_PART, UUID.randomUUID(), null, null, partAId, null ,
                ResponseMode.HINT, "hint me"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(14)
    @DisplayName("QUESTION_PART: CHECK pre-attempt 409 BEFORE generation; post-attempt feedback is part-scoped")
    void partCheckGatingAndPartScopedFeedback() throws Exception {
        seedPartQuestion();
        UUID learner = freshLearner();

        // pre-attempt: the deterministic gate refuses before any generation
        int generatorCallsBefore = generator.calls.get();
        assertThatThrownBy(() -> cla.contextualAsk(learner,
                ResourceContext.Kind.QUESTION_PART, null, null, null, partAId, null ,
                ResponseMode.CHECK, "check my part answer"))
                .isInstanceOf(AttemptRequiredException.class);
        assertThat(generator.calls.get()).isEqualTo(generatorCallsBefore);

        // real learner attempt through the REAL assessment pipeline (both parts)
        assessment.submitStructured(learner, new com.syllabai.assessment.dto.StructuredSubmitRequest(
                partQuestionId,
                List.of(
                        new com.syllabai.assessment.dto.PartAnswerRequest(
                                partAId, "ions are free to move when molten"),
                        new com.syllabai.assessment.dto.PartAnswerRequest(
                                partBId, "solid ions vibrate about fixed positions")),
                30000L, 4, false, false));

        // post-attempt: the gate unlocks and feedback grounds on the PART's points
        ClaAnswerView answer = cla.contextualAsk(learner,
                ResourceContext.Kind.QUESTION_PART, null, null, null, partAId, null ,
                ResponseMode.CHECK, "check my part a answer");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().attempted()).isTrue();
        assertThat(answer.context().mode()).isEqualTo(ResponseMode.CHECK);
        var evidence = generator.lastContext.evidence();
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.source()).isEqualTo(com.syllabai.tutor.EvidenceItem.EvidenceSource.MARK_SCHEME);
            // PART-SCOPED: this part's + question-level points only
            assertThat(item.content()).contains("PART A POINT")
                    .contains("QUESTION LEVEL POINT")
                    .doesNotContain("SIBLING PART B POINT");
        });
        // the learner's OWN submitted work is in the SOURCES (§7.3: CHECK
        // reviews the learner's submitted answers), part-scoped
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.source()).isEqualTo(com.syllabai.tutor.EvidenceItem.EvidenceSource.LEARNER_WORK);
            assertThat(item.content()).contains("ions are free to move when molten")
                    .doesNotContain("solid ions vibrate about fixed positions");
        });

        // the exchange landed in LIM with the PART identity as contextReference
        List<TutorTopicEngagement> rows = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learner, java.time.Instant.now().minusSeconds(3600));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
            assertThat(row.contextKind()).isEqualTo("QUESTION_PART");
            assertThat(row.contextReference()).isEqualTo(partAId);
            assertThat(row.responseMode()).isEqualTo("CHECK");
            assertThat(row.nodeId()).isEqualTo(partAnchorNodeId);
        });
    }

    @Test
    @Order(15)
    @DisplayName("QUESTION_PART over HTTP: missing partId is a 400; unknown part is a 404")
    void partHttpFailSafe() throws Exception {
        seedPartQuestion();
        String email = "cla-http4-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner 4"));
        String token = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // missing partId for the declared kind → 400
        HttpResponse<String> missingRef = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "QUESTION_PART", "mode": "HINT", "question": "hint me"}
                """);
        assertThat(missingRef.statusCode()).isEqualTo(400);

        // unknown part → 404, same shape as an unauthorized one (no oracle)
        HttpResponse<String> unknown = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "QUESTION_PART", "partId": "%s", "mode": "HINT", "question": "hint me"}
                """.formatted(UUID.randomUUID()));
        assertThat(unknown.statusCode()).isEqualTo(404);

        // control: the real part IS served (the negatives above are not vacuous)
        HttpResponse<String> ok = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "QUESTION_PART", "partId": "%s", "mode": "HINT", "question": "hint me"}
                """.formatted(partAId));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("QUESTION_PART").contains("\"partLabel\":\"a\"");
    }

    // ── SMART_LESSON: topic-anchored lesson context + deterministic action ───

    @Test
    @Order(16)
    @DisplayName("SMART_LESSON: the lesson anchors the grounded pipeline and carries the learner's deterministic action")
    void smartLessonFlow() throws Exception {
        seed();
        UUID learner = freshLearner();

        ClaAnswerView answer = cla.contextualAsk(learner,
                ResourceContext.Kind.SMART_LESSON, rootId, topicId, null, null, null ,
                ResponseMode.EXPLAIN, "help me with this lesson");

        assertThat(answer.refused()).isFalse();
        assertThat(answer.context().kind()).isEqualTo("SMART_LESSON");
        assertThat(answer.context().reference()).isEqualTo(topicId);
        assertThat(answer.context().topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(answer.context().validationState()).isEqualTo("VALIDATED");
        // the deterministic lesson decision rides on the resolved context —
        // a fresh learner's honest next action on this topic (never invented)
        assertThat(answer.context().lessonAction()).isNotNull();
        assertThat(answer.context().lessonAction().actionType()).isNotBlank();
        assertThat(answer.context().lessonAction().reasonCode()).isNotBlank();
        // grounding is the SAME curriculum spine (anchor + spec structure), and
        // the ladder's decision entered the brief as framing only
        assertThat(answer.evidenceCount()).isGreaterThanOrEqualTo(1);
        assertThat(answer.citations()).isNotEmpty();
        assertThat(generator.lastContext.learnerBrief()).contains("Smart Lesson next action");
        assertThat(generator.lastContext.interventionPlan().rationale())
                .contains("CLA EXPLAIN mode").contains("anchored Smart Lesson");

        // LIM provenance: contextKind=SMART_LESSON, topic anchor as reference
        List<TutorTopicEngagement> rows = engagements
                .findByLearnerIdAndOccurredAtGreaterThanEqualOrderByOccurredAtDesc(
                        learner, java.time.Instant.now().minusSeconds(3600));
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.surface()).isEqualTo("CONTEXTUAL_ASSISTANT");
            assertThat(row.contextKind()).isEqualTo("SMART_LESSON");
            assertThat(row.contextReference()).isEqualTo(topicId);
            assertThat(row.nodeId()).isEqualTo(topicId);
            assertThat(row.responseMode()).isEqualTo("EXPLAIN");
        });

        // the exchange did not mutate the learner model (no mastery write from chat)
        Integer skillsAfter = jdbc.queryForObject(
                "select count(*) from skill_states where learner_id = ?",
                Integer.class, learner);
        assertThat(skillsAfter).isZero();
    }

    @Test
    @Order(17)
    @DisplayName("SMART_LESSON fail-closed over HTTP: missing topicNodeId 400, unknown/foreign topic 404")
    void smartLessonHttpFailSafe() throws Exception {
        seed();
        String email = "cla-http5-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test";
        authService.register(new RegisterRequest(email, "ItLearner123!", "Http Learner 5"));
        String token = authService.login(new LoginRequest(email, "ItLearner123!")).accessToken();

        // missing topicNodeId for the declared kind → 400
        HttpResponse<String> missingRef = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "SMART_LESSON", "rootId": "%s", "mode": "EXPLAIN", "question": "help"}
                """.formatted(rootId));
        assertThat(missingRef.statusCode()).isEqualTo(400);

        // unknown topic → 404, same shape as a foreign one (no existence oracle)
        HttpResponse<String> unknown = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "SMART_LESSON", "rootId": "%s", "topicNodeId": "%s",
                 "mode": "EXPLAIN", "question": "help"}
                """.formatted(rootId, UUID.randomUUID()));
        assertThat(unknown.statusCode()).isEqualTo(404);

        // control: the real lesson IS served (the negatives are not vacuous)
        HttpResponse<String> ok = post("/api/v1/learners/me/cla/ask", token,
                """
                {"kind": "SMART_LESSON", "rootId": "%s", "topicNodeId": "%s",
                 "mode": "EXPLAIN", "question": "help me with this lesson"}
                """.formatted(rootId, topicId));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("SMART_LESSON").contains("lessonAction");
    }
}
