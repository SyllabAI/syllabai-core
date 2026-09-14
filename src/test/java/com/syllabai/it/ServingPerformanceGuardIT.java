package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.learner.SmartLessonService;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Performance regression guard (productization \u00a78): the serving surfaces
 * must stay BATCHED. Session-70 fixed an N+1 that took the unscoped question
 * list from ~85.6s to ~2.2s warm (25d016f); this IT pins the fix by counting
 * REAL SQL statements against a real Postgres \u2014 a reintroduced per-question
 * version/parts fetch (or a per-item graph walk) fails the bound long before
 * anyone notices a slow page.
 *
 * <p>Bounds are set with headroom above the current batched shapes but far
 * below the N+1 shapes (which scale with the question count): the fixture
 * ingests a 12-question paper, so a 2-3-queries-per-question regression would
 * issue 24-36+ statements where the guards allow ~10-20.</p>
 */
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class ServingPerformanceGuardIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final int QUESTIONS = 12;

    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private ServableQuestionService servableQuestions;
    @Autowired
    private KnowledgeGraphService knowledgeGraph;
    @Autowired
    private SmartLessonService smartLesson;
    @Autowired
    private AuthService authService;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private EntityManagerFactory emf;

    /** one paper, QUESTIONS structured questions, every part with a scheme point */
    private PastPaperDraftDto bigDraft() {
        List<PastPaperDraftDto.QuestionDraft> qs = new ArrayList<>();
        List<PastPaperDraftDto.MarkPointDraft> points = new ArrayList<>();
        for (int i = 1; i <= QUESTIONS; i++) {
            qs.add(new PastPaperDraftDto.QuestionDraft(
                    "q" + i, String.valueOf(i), "Question " + i + " stem", "Explain",
                    3, "STRUCTURED", 1, 0.8,
                    List.of(new PastPaperDraftDto.PartDraft("a", "Part a prompt", "State", 3, 0.8))));
            points.add(new PastPaperDraftDto.MarkPointDraft(
                    i + "-a", 1, "the correct content", 3, List.of(), 0.8));
        }
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2013-" + UUID.randomUUID().toString().substring(0, 6),
                        "PERFG", "perf-qp-doc", "perf-ms-doc"),
                qs,
                new PastPaperDraftDto.MarkSchemeDraft("1", "perf-ms-doc", points),
                "it-test-method",
                true);
    }

    private Statistics stats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    /** ingest + fully validate the 12-question paper; returns its paper id */
    private UUID validatedPaper() {
        var summary = ingestion.ingest(bigDraft(), UUID.randomUUID());
        for (Question q : questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId())).toList()) {
            QuestionVersion version = questionVersions
                    .findByQuestionIdOrderByVersionDesc(q.id()).get(0);
            review.validateQuestionVersion(version.id());
            MarkScheme scheme = markSchemes
                    .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
            review.validateMarkScheme(scheme.id(), List.of());
        }
        review.validatePaper(summary.paperId());
        return summary.paperId();
    }

    @Test
    @DisplayName("unscoped list serving stays batched: bounded SQL regardless of question count")
    void listServingStaysBatched() {
        validatedPaper();
        int served = servableQuestions.allActive().size();
        assertThat(served).isGreaterThanOrEqualTo(QUESTIONS);

        Statistics stats = stats();
        stats.clear();
        List<?> again = servableQuestions.allActive();
        assertThat(again).hasSize(served);
        long statements = stats.getPrepareStatementCount();
        // batched shape: a handful of queries; the N+1 shape would be ~2-3 per
        // question (>= 24 at this corpus size) before counting parts/options
        assertThat(statements)
                .as("allActive issued %d statements for %d questions (N+1 regression?)",
                        statements, served)
                .isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("subject-scoped serving stays batched (subtree + activeWithin)")
    void scopedServingStaysBatched() {
        UUID paperId = validatedPaper();
        Question any = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> paperId.equals(q.examPaperId())).findFirst().orElseThrow();
        UUID anchor = any.primaryTopicNodeId();
        assertThat(anchor).isNotNull();

        Statistics stats = stats();
        stats.clear();
        var scoped = servableQuestions.activeWithin(List.of(anchor));
        assertThat(scoped).hasSize(QUESTIONS);
        long statements = stats.getPrepareStatementCount();
        assertThat(statements)
                .as("activeWithin issued %d statements (per-question regression?)", statements)
                .isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("knowledge tree assembly stays batched (no per-node graph walks)")
    void knowledgeTreeStaysBatched() {
        UUID paperId = validatedPaper();
        Question any = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> paperId.equals(q.examPaperId())).findFirst().orElseThrow();
        // walk up from the anchor to a subject-ish root: use the anchor's own
        // subtree first, then the tree call on the anchor (the KG tree under a
        // paper anchor is small but exercises the same batched builder)
        Statistics stats = stats();
        stats.clear();
        var tree = knowledgeGraph.treeWithMisconceptions(any.primaryTopicNodeId());
        assertThat(tree).isNotNull();
        long statements = stats.getPrepareStatementCount();
        assertThat(statements)
                .as("treeWithMisconceptions issued %d statements (per-node regression?)",
                        statements)
                .isLessThanOrEqualTo(15);
    }

    @Test
    @DisplayName("Smart Lesson decision stays bounded (no per-topic scans in the ladder)")
    void smartLessonStaysBounded() {
        UUID paperId = validatedPaper();
        Question any = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> paperId.equals(q.examPaperId())).findFirst().orElseThrow();
        UUID learner = authService.register(new RegisterRequest(
                "it-perf-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
        UUID anchor = any.primaryTopicNodeId();

        Statistics stats = stats();
        stats.clear();
        var lesson = smartLesson.lessonFor(learner, anchor, anchor);
        assertThat(lesson.action()).isNotNull();
        long statements = stats.getPrepareStatementCount();
        // the ladder reads evidence maps + one tree + one batched activeWithin;
        // the advance walk must NOT scan topics one by one
        assertThat(statements)
                .as("smartLesson issued %d statements (per-topic regression?)", statements)
                .isLessThanOrEqualTo(40);
    }
}
