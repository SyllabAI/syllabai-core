package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.CourseStatsController;
import com.syllabai.learner.FlashcardRatingController;
import com.syllabai.learner.FlashcardRatingController.FlashcardRatingRequest;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.CourseStatsView;
import com.syllabai.revisionnotes.RevisionNote;
import com.syllabai.revisionnotes.RevisionNoteRepository;
import com.syllabai.revisionnotes.RevisionNoteService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
 * Integration test: the course-stats read model (ADR-029 tranche 4.10)
 * against a real Postgres. Pins the whole contract end to end:
 *
 * <ul>
 *   <li>the four aggregates computed over the FULL evidence tables, through
 *       the real write paths — attempt volume counts retries while the
 *       question/note/card counts are DISTINCT coverage (a re-rated card
 *       and a re-viewed idempotent note each count once);</li>
 *   <li>a fresh learner sees honest zeros (no fabricated activity);</li>
 *   <li>learner isolation — one learner's coverage never appears in
 *       another's stats;</li>
 *   <li>THE HONESTY PIN — coverage is exposure, never mastery:
 *       SkillStateRepository stays empty after all this activity and the
 *       learner-state view's skillStates stay empty too.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class CourseStatsFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SUBTOPIC_CODE = "4CH1-S1-a";

    @Autowired
    private AuthService authService;
    @Autowired
    private CourseStatsController statsController;
    @Autowired
    private AttemptRepository attemptRows;
    @Autowired
    private QuestionRepository questionRows;
    @Autowired
    private FlashcardRatingController ratingsController;
    @Autowired
    private RevisionNoteService noteService;
    @Autowired
    private RevisionNoteRepository noteRows;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    private static Question questionA;
    private static Question questionB;

    @BeforeAll
    static void seedCorpus(@Autowired KnowledgeNodeRepository knowledgeNodes,
                           @Autowired QuestionRepository questionRows,
                           @Autowired RevisionNoteRepository noteRows) {
        // rating anchors need a curriculum-structure node (same fixture the
        // V47/V48 ITs use); questions also need a primary topic node
        KnowledgeNode topic = knowledgeNodes.save(new KnowledgeNode(
                SUBTOPIC_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));

        // two distinct questions → attempt volume vs distinct coverage is
        // assertable (retry qA, touch qB once)
        questionA = questionRows.save(new Question("sme-eq-it-cs-q1",
                Question.Type.STRUCTURED, "IT fixture stem A", 4, 3, 120,
                "Explain", topic.id(), Question.Provenance.PAST_PAPER));
        questionB = questionRows.save(new Question("sme-eq-it-cs-q2",
                Question.Type.MCQ_SINGLE, "IT fixture stem B", 1, 3, 60,
                "State", topic.id(), Question.Provenance.PAST_PAPER));

        // two corpus notes → the idempotent view marker is observable
        noteRows.save(new RevisionNote("rn_it_cs_1", 1, "Topic", 1, "Subtopic",
                1, "IT note one", "body", "{}", "4CH1-1.1", null,
                Instant.now(), "it"));
        noteRows.save(new RevisionNote("rn_it_cs_2", 1, "Topic", 1, "Subtopic",
                2, "IT note two", "body", "{}", "4CH1-1.2", null,
                Instant.now(), "it"));
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-cs-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** drive every evidence class through its real write path */
    private void doFullActivity(UUID learner) {
        // attempts: retry qA (volume 3, distinct 2)
        attemptRows.save(new Attempt(learner, questionA, null, false, null,
                1000L, 3, false, false, "it"));
        attemptRows.save(new Attempt(learner, questionA, null, false, null,
                1200L, 3, false, false, "it"));
        attemptRows.save(new Attempt(learner, questionB, null, true, null,
                800L, 4, false, false, "it"));

        // flashcard ratings: rate c1, re-rate c1 (append-only → 2 rows), rate c2
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_it_cs_c1", "know", SUBTOPIC_CODE));
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_it_cs_c1", "still-learning", SUBTOPIC_CODE));
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_it_cs_c2", "know", SUBTOPIC_CODE));

        // note views: view note 1, view it AGAIN (idempotent), view note 2
        noteService.markViewed(learner, "rn_it_cs_1");
        noteService.markViewed(learner, "rn_it_cs_1");
        noteService.markViewed(learner, "rn_it_cs_2");
    }

    @Test
    @DisplayName("aggregates over the full trail: volume counts retries, coverage counts distinct entities")
    void aggregatesOverFullTrail() {
        UUID learner = newLearner();
        doFullActivity(learner);

        CourseStatsView stats = statsController.courseStats(learner);
        assertThat(stats.learnerId()).isEqualTo(learner);
        // attempt volume includes the retry
        assertThat(stats.attempts()).isEqualTo(3);
        // ...but coverage counts the two distinct questions once each
        assertThat(stats.distinctQuestions()).isEqualTo(2);
        // the idempotent view marker: re-viewing note 1 never double-counts
        assertThat(stats.notesViewed()).isEqualTo(2);
        // the append-only rating trail: re-rated c1 counts once
        assertThat(stats.flashcardsRated()).isEqualTo(2);
    }

    @Test
    @DisplayName("a fresh learner sees honest zeros — no fabricated activity")
    void freshLearnerZeros() {
        UUID learner = newLearner();
        CourseStatsView stats = statsController.courseStats(learner);
        assertThat(stats.attempts()).isZero();
        assertThat(stats.distinctQuestions()).isZero();
        assertThat(stats.notesViewed()).isZero();
        assertThat(stats.flashcardsRated()).isZero();
    }

    @Test
    @DisplayName("learner isolation: one learner's coverage never appears in another's stats")
    void learnerIsolation() {
        UUID a = newLearner();
        UUID b = newLearner();
        doFullActivity(a);

        CourseStatsView statsB = statsController.courseStats(b);
        assertThat(statsB.attempts()).isZero();
        assertThat(statsB.distinctQuestions()).isZero();
        assertThat(statsB.notesViewed()).isZero();
        assertThat(statsB.flashcardsRated()).isZero();

        CourseStatsView statsA = statsController.courseStats(a);
        assertThat(statsA.attempts()).isEqualTo(3);
        assertThat(statsA.distinctQuestions()).isEqualTo(2);
    }

    @Test
    @DisplayName("THE HONESTY PIN: coverage is exposure, never mastery — SkillState stays empty")
    void coverageNeverProducesMastery() {
        UUID learner = newLearner();
        doFullActivity(learner);

        // all that activity, and yet: zero mastery state. Mastery comes from
        // marked attempts ONLY, fired by the evidence pipeline — never from
        // a coverage read model.
        assertThat(skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learner)).isEmpty();
        assertThat(stateController.state(learner).skillStates()).isEmpty();
    }
}
