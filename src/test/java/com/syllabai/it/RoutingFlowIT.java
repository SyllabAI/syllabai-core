package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.content.EnumerateService;
import com.syllabai.content.EnumerateService.EnumerateResult;
import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.content.FetchService;
import com.syllabai.content.FetchService.FetchResult;
import com.syllabai.curriculum.CurriculumScope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Routing integration test (R4, plan §7): the deterministic Fetch and
 * Enumerate paths against a real seeded bank — the metadata-intent surfaces
 * the R3 instrument proved content arms cannot serve (FETCH 0/40).
 *
 * <ul>
 *   <li>Fetch resolves paper + question + mark-scheme points from pure
 *       metadata (zero vector calls, V35 canonical series/year columns);</li>
 *   <li>§8.2 superseded duplicates (REJECTED, no paper_code) are invisible to
 *       resolution — the retained coded twin answers instead;</li>
 *   <li>Enumerate serves topic / spec-point / paper axes with gold-set
 *       semantics (PAST_PAPER + active; year windows off exam_papers.year);</li>
 *   <li>an unparseable query is an honest empty result flagged as a parse
 *       defect; unscoped calls are rejected before any SQL (T-C07).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoutingFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final UUID CURRICULUM = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-00000000d002");
    private static final UUID TOPIC_NODE = UUID.fromString("00000000-0000-0000-0000-00000000d003");
    private static final UUID SPEC_NODE = UUID.fromString("00000000-0000-0000-0000-00000000d004");
    private static final UUID PAPER_JAN_2022 = UUID.fromString("00000000-0000-0000-0000-00000000d011");
    private static final UUID PAPER_JUN_2011 = UUID.fromString("00000000-0000-0000-0000-00000000d012");
    private static final UUID PAPER_DUP = UUID.fromString("00000000-0000-0000-0000-00000000d013");
    private static final UUID Q_JAN_2022_Q1 = UUID.fromString("00000000-0000-0000-0000-00000000d021");
    private static final UUID Q_JAN_2022_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d022");
    private static final UUID Q_DUP_Q1 = UUID.fromString("00000000-0000-0000-0000-00000000d023");
    private static final UUID Q_PAPERLESS = UUID.fromString("00000000-0000-0000-0000-00000000d024");
    private static final UUID VER_Q1 = UUID.fromString("00000000-0000-0000-0000-00000000d031");
    private static final UUID VER_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d032");
    private static final UUID VER_DUP = UUID.fromString("00000000-0000-0000-0000-00000000d033");
    private static final UUID SCHEME_Q1 = UUID.fromString("00000000-0000-0000-0000-00000000d041");
    private static final UUID SCHEME_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d042");
    private static final UUID POINT_Q1 = UUID.fromString("00000000-0000-0000-0000-00000000d051");
    private static final UUID POINT_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d052");
    private static final UUID TOPIC_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d061");
    private static final UUID TOPIC_PAPERLESS = UUID.fromString("00000000-0000-0000-0000-00000000d062");
    private static final UUID SPEC_Q2 = UUID.fromString("00000000-0000-0000-0000-00000000d063");

    @Autowired
    private FetchService fetch;
    @Autowired
    private EnumerateService enumerate;
    @Autowired
    private JdbcTemplate jdbc;

    private CurriculumScope scope;

    @BeforeAll
    void seed() {
        jdbc.update("""
                insert into curriculum_versions (id, board, qualification, code, title, status, created_at)
                values (?, 'Pearson Edexcel', 'International GCSE', '4CH1-IT', 'IT curriculum', 'ACTIVE', now())
                """, CURRICULUM);
        jdbc.update("""
                insert into subjects (id, curriculum_version_id, code, name, created_at)
                values (?, ?, '4CH1', 'Chemistry (4CH1)', now())
                """, SUBJECT, CURRICULUM);
        scope = new CurriculumScope(CURRICULUM, "4CH1-IT", Set.of());

        jdbc.update("""
                insert into knowledge_nodes (id, code, node_type, title, validation_status, created_at)
                values (?, '4CH1-S2-c', 'TOPIC', 'Electrolysis', 'VALIDATED', now())
                """, TOPIC_NODE);
        jdbc.update("""
                insert into knowledge_nodes (id, code, node_type, title, validation_status, created_at)
                values (?, '4CH1-2.36', 'SUBTOPIC', 'Electrolysis of aqueous salts', 'VALIDATED', now())
                """, SPEC_NODE);

        paper(PAPER_JAN_2022, "4CH1/2C", "January 2022", "JAN", 2022);
        paper(PAPER_JUN_2011, "4CH0/1C", "June 2011", "JUN", 2011);
        // §8.2 duplicate posture: legacy import with no code, superseded — REJECTED
        paper(PAPER_DUP, null, "June 2011", "JUN", 2011);
        jdbc.update("update exam_papers set validation_state = 'REJECTED' where id = ?", PAPER_DUP);

        question(Q_JAN_2022_Q1, VER_Q1, PAPER_JAN_2022, "q01-d021",
                "Describe electrolysis of molten salts.", 8);
        question(Q_JAN_2022_Q2, VER_Q2, PAPER_JAN_2022, "q02-d022",
                "Explain why the rate is faster at higher temperature.", 6);
        question(Q_DUP_Q1, VER_DUP, PAPER_DUP, "q01-d023",
                "Superseded duplicate stem — never served.", 5);

        markScheme(SCHEME_Q2, VER_Q2, POINT_Q2, "allow reverse argument", 2);
        markScheme(SCHEME_Q1, VER_Q1, POINT_Q1, "M1 salt must be molten", 4);

        topicMapping(TOPIC_Q2, Q_JAN_2022_Q2, TOPIC_NODE);
        specMapping(SPEC_Q2, Q_JAN_2022_Q2, SPEC_NODE);
        // one paperless PAST_PAPER question mapped to the topic (disjoint bank subset, R3 finding)
        jdbc.update("""
                insert into questions (id, question_type, stem, marks, difficulty, expected_time_seconds,
                                       provenance, active, version, created_at)
                values (?, 'STRUCTURED', 'Paperless bank question about electrolysis.', 4, 3, 60,
                        'PAST_PAPER', true, 1, now())
                """, Q_PAPERLESS);
        topicMapping(TOPIC_PAPERLESS, Q_PAPERLESS, TOPIC_NODE);
    }

    private void paper(UUID id, String code, String sessionLabel, String series, int year) {
        jdbc.update("""
                insert into exam_papers (id, subject_id, title, board, qualification, session_label,
                                         paper_code, validation_state, provenance, created_at)
                values (?, ?, ?, 'Pearson Edexcel', 'International GCSE', ?, ?, 'SUGGESTED', 'PAST_PAPER', now())
                """, id, SUBJECT, sessionLabel, sessionLabel, code);
        jdbc.update("update exam_papers set series = ?, year = ? where id = ?", series, year, id);
    }

    private void question(UUID id, UUID versionId, UUID paperId, String externalRef,
                          String stem, int marks) {
        jdbc.update("""
                insert into questions (id, external_ref, question_type, stem, marks, difficulty,
                                       expected_time_seconds, provenance, active, version,
                                       exam_paper_id, created_at)
                values (?, ?, 'STRUCTURED', ?, ?, 3, 90, 'PAST_PAPER', true, 1, ?, now())
                """, id, externalRef, stem, marks, paperId);
        jdbc.update("""
                insert into question_versions (id, question_id, version, stem, marks, difficulty,
                                               expected_time_seconds, validation_state, created_at)
                values (?, ?, 1, ?, ?, 3, 90, 'SUGGESTED', now())
                """, versionId, id, stem, marks);
    }

    private void markScheme(UUID schemeId, UUID versionId, UUID pointId, String pointText, int marks) {
        jdbc.update("""
                insert into mark_schemes (id, question_version_id, version_label, validation_state, created_at)
                values (?, ?, '1', 'VALIDATED', now())
                """, schemeId, versionId);
        jdbc.update("""
                insert into mark_points (id, mark_scheme_id, ref, ordering, text, marks, created_at)
                values (?, ?, 'M1', 1, ?, ?, now())
                """, pointId, schemeId, pointText, marks);
    }

    private void topicMapping(UUID mappingId, UUID questionId, UUID nodeId) {
        jdbc.update("""
                insert into question_topics (id, question_id, node_id, is_primary, created_at)
                values (?, ?, ?, true, now())
                """, mappingId, questionId, nodeId);
    }

    private void specMapping(UUID mappingId, UUID questionId, UUID nodeId) {
        jdbc.update("""
                insert into question_spec_points (id, question_id, spec_point_node_id, role, created_at)
                values (?, ?, ?, 'PRIMARY', now())
                """, mappingId, questionId, nodeId);
    }

    @Test
    @Order(1)
    @DisplayName("fetch resolves paper + question + MS points from pure metadata")
    void fetchResolves() {
        FetchResult result = fetch.fetch("mark scheme for q2 january 2022 paper 4CH1/2C", scope);
        assertThat(result.parseDefect()).isFalse();
        assertThat(result.ambiguous()).isFalse();
        assertThat(result.papers()).hasSize(1);
        FetchService.FetchPaperHit hit = result.papers().get(0);
        assertThat(hit.paperCode()).isEqualTo("4CH1/2C");
        assertThat(hit.series()).isEqualTo("JAN");
        assertThat(hit.year()).isEqualTo(2022);
        assertThat(hit.question()).isNotNull();
        assertThat(hit.question().externalRef()).isEqualTo("q02-d022");
        assertThat(hit.question().stem()).contains("rate is faster");
        assertThat(hit.question().markPoints()).extracting("text")
                .containsExactly("allow reverse argument");
        ParsedFetchQuery parsed = result.parsed();
        assertThat(parsed.series()).isEqualTo("JAN");
        assertThat(parsed.qnum()).isEqualTo(2);
        assertThat(parsed.paperCode()).isEqualTo("4CH1/2C");
        assertThat(parsed.msSeeking()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("§8.2 superseded duplicates never resolve — the coded twin answers")
    void duplicateInvisible() {
        FetchResult result = fetch.fetch("give me the answer to q1 from june 2011", scope);
        assertThat(result.parseDefect()).isFalse();
        assertThat(result.papers()).hasSize(1);
        assertThat(result.papers().get(0).paperId()).isEqualTo(PAPER_JUN_2011);
        assertThat(result.papers().stream()
                .map(p -> p.question() == null ? null : p.question().externalRef())
                .toList()).doesNotContain("q01-d023");
    }

    @Test
    @Order(3)
    @DisplayName("enumerate topic axis: papered + paperless PAST_PAPER questions, dedup keys stable")
    void enumerateTopic() {
        EnumerateResult result = enumerate.enumerate("list all questions about Electrolysis", scope);
        assertThat(result.mode()).isEqualTo("topic");
        assertThat(result.resolvedNodeCode()).isEqualTo("4CH1-S2-c");
        assertThat(result.questions()).hasSize(2);
        assertThat(result.questions()).allSatisfy(q -> assertThat(q.questionId()).isNotNull());
        assertThat(result.questions().stream().map(q -> q.dedupKey()).distinct().count()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @DisplayName("enumerate spec axis via statement code")
    void enumerateSpec() {
        EnumerateResult result = enumerate.enumerate(
                "all questions for specification point 4CH1-2.36", scope);
        assertThat(result.mode()).isEqualTo("spec");
        assertThat(result.resolvedNodeCode()).isEqualTo("4CH1-2.36");
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).externalRef()).isEqualTo("q02-d022");
    }

    @Test
    @Order(5)
    @DisplayName("enumerate paper axis: whole-paper question list; rejected dup excluded")
    void enumeratePaper() {
        EnumerateResult result = enumerate.enumerate(
                "every question in the June 2011 paper 4CH0/1C", scope);
        assertThat(result.mode()).isEqualTo("paper");
        assertThat(result.ambiguous()).isFalse();
        assertThat(result.questions()).extracting("externalRef")
                .containsExactly("q01-d021")
                .doesNotContain("q01-d023");
    }

    @Test
    @Order(6)
    @DisplayName("year window filters the topic axis via exam_papers.year")
    void yearWindow() {
        EnumerateResult hit = enumerate.enumerate(
                "all questions about Electrolysis from 2022 to 2022", scope);
        assertThat(hit.questions()).hasSize(1);
        assertThat(hit.yearFrom()).isEqualTo(2022);
        EnumerateResult miss = enumerate.enumerate(
                "all questions about Electrolysis from 1999 to 2000", scope);
        assertThat(miss.questions()).isEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("unparseable fetch query is an honest empty parse-defect result")
    void parseDefect() {
        FetchResult result = fetch.fetch("tell me about electrolysis please", scope);
        assertThat(result.parseDefect()).isTrue();
        assertThat(result.papers()).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("unscoped fetch/enumerate are rejected before any SQL (T-C07)")
    void unscopedRejected() {
        assertThatThrownBy(() -> fetch.fetch("q1 january 2022", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enumerate.enumerate("list all questions about electrolysis", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
