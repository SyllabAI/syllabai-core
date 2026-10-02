package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.FlashcardRatingController;
import com.syllabai.learner.FlashcardRatingController.FlashcardRatingRequest;
import com.syllabai.learner.FlashcardRatingRepository;
import com.syllabai.learner.FlashcardReviewScheduleController;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.FlashcardReviewScheduleView;
import com.syllabai.learner.dto.LearnerStateView;
import java.time.Duration;
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
 * Integration test: the flashcard review-schedule feed (T-C53 — GET
 * /api/v1/learners/me/flashcard-review-schedule) against a real Postgres.
 * Pins the whole contract end to end:
 *
 * <ul>
 *   <li>the Ebbinghaus queue derives from the append-only ratings trail: a
 *       fresh "know" is scheduled (streak 1 → 1d, not due), a "still
 *       learning" is due immediately, a re-rate resets the clock — and the
 *       derived dueAt is EXACTLY lastRatedAt + intervalDays (the feed carries
 *       its own arithmetic, no clock tolerance needed);</li>
 *   <li>attribution echoes the resolved deck anchor code;</li>
 *   <li>the summary is coherent with the cards (due/scheduled/nextDueAt);</li>
 *   <li>learner isolation — another learner's trail never appears;</li>
 *   <li>THE HONESTY PIN, feed edition — the queue serves from the trail while
 *       SkillState stays untouched: ratings are TIMING evidence only, the
 *       schedule is computed at read and persisted nowhere (ADR-031).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class FlashcardReviewScheduleFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SUBTOPIC_CODE = "4CH1-S1-a";
    private static UUID subtopicNodeId;

    @Autowired
    private AuthService authService;
    @Autowired
    private FlashcardRatingController ratingsController;
    @Autowired
    private FlashcardReviewScheduleController scheduleController;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private FlashcardRatingRepository ratingRows;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        // mirrors the production 4CH1 graph's level naming (same fixture as
        // FlashcardRatingFlowIT): the deck-anchor level ingests as TOPIC
        KnowledgeNode subtopic = knowledgeNodes.save(new KnowledgeNode(
                SUBTOPIC_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        subtopicNodeId = subtopic.id();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-fcs-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** pace the POSTs like a human deck flip — occurred_at ties make trail
     *  order ambiguous (production never sees ties; FlashcardRatingFlowIT's
     *  lesson carried over) */
    private void pace() throws InterruptedException {
        Thread.sleep(60);
    }

    @Test
    @DisplayName("empty learner: an honest empty feed — nothing invented for never-rated cards")
    void emptyFeedForNewLearner() {
        UUID learner = newLearner();
        FlashcardReviewScheduleView feed = scheduleController.schedule(learner);
        assertThat(feed.cards()).isEmpty();
        assertThat(feed.summary().due()).isZero();
        assertThat(feed.summary().scheduled()).isZero();
        assertThat(feed.summary().nextDueAt()).isNull();
        assertThat(feed.learnerId()).isEqualTo(learner);
        assertThat(feed.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("feed shape: fresh know scheduled (+1d, not due), still-learning due now, summary coherent, anchor echoed")
    void feedShapeAndSummary() throws InterruptedException {
        UUID learner = newLearner();

        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_sched_known", "know", SUBTOPIC_CODE));
        pace();
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_sched_fresh", "still-learning", SUBTOPIC_CODE));
        pace();

        FlashcardReviewScheduleView feed = scheduleController.schedule(learner);
        assertThat(feed.cards()).hasSize(2);

        // due cards surface first (stalest dueAt first): the still-learning
        // card (dueAt = its occurredAt) leads the scheduled know card
        var dueCard = feed.cards().get(0);
        assertThat(dueCard.cardId()).isEqualTo("fl_sched_fresh");
        assertThat(dueCard.rating()).isEqualTo("still-learning");
        assertThat(dueCard.streak()).isZero();
        assertThat(dueCard.intervalDays()).isZero();
        assertThat(dueCard.due()).isTrue();
        assertThat(dueCard.dueAt()).isEqualTo(dueCard.lastRatedAt());

        var scheduledCard = feed.cards().get(1);
        assertThat(scheduledCard.cardId()).isEqualTo("fl_sched_known");
        assertThat(scheduledCard.rating()).isEqualTo("know");
        assertThat(scheduledCard.streak()).isEqualTo(1);
        assertThat(scheduledCard.intervalDays()).isEqualTo(1);
        assertThat(scheduledCard.due()).isFalse();
        // the feed carries its own arithmetic: dueAt is EXACTLY lastRatedAt + intervalDays
        assertThat(scheduledCard.dueAt())
                .isEqualTo(scheduledCard.lastRatedAt().plus(Duration.ofDays(1)));

        // attribution echoes the resolved deck anchor (and its node id)
        assertThat(dueCard.subtopicCode()).isEqualTo(SUBTOPIC_CODE);
        assertThat(scheduledCard.subtopicCode()).isEqualTo(SUBTOPIC_CODE);
        assertThat(dueCard.nodeId()).isEqualTo(subtopicNodeId);

        // summary mirrors the hub drawer's queue semantics
        assertThat(feed.summary().due()).isEqualTo(1);
        assertThat(feed.summary().scheduled()).isEqualTo(1);
        assertThat(feed.summary().nextDueAt()).isEqualTo(scheduledCard.dueAt());
    }

    @Test
    @DisplayName("re-rate resets the clock: know → still-learning → know rides the trail tail, one card in the feed")
    void reRateResetsTheFeed() throws InterruptedException {
        UUID learner = newLearner();

        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_rr", "know", SUBTOPIC_CODE));
        pace();
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_rr", "still-learning", SUBTOPIC_CODE));
        pace();

        // the still-learning tail: due now, despite the earlier know
        FlashcardReviewScheduleView dropped = scheduleController.schedule(learner);
        assertThat(dropped.cards()).hasSize(1); // grouped: 2 events, ONE card
        assertThat(dropped.cards().get(0).streak()).isZero();
        assertThat(dropped.cards().get(0).due()).isTrue();

        pace();
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_rr", "know", SUBTOPIC_CODE));
        assertThat(ratingRows.findByLearnerIdOrderByCardIdAscOccurredAtAscIdAsc(learner)).hasSize(3);

        FlashcardReviewScheduleView restarted = scheduleController.schedule(learner);
        assertThat(restarted.cards()).hasSize(1);
        assertThat(restarted.cards().get(0).rating()).isEqualTo("know");
        assertThat(restarted.cards().get(0).streak()).isEqualTo(1);
        assertThat(restarted.cards().get(0).intervalDays()).isEqualTo(1);
        assertThat(restarted.cards().get(0).due()).isFalse();
        assertThat(restarted.summary().due()).isZero();
        assertThat(restarted.summary().scheduled()).isEqualTo(1);
    }

    @Test
    @DisplayName("learner isolation: another learner's trail never appears in my feed")
    void learnerIsolation() {
        UUID a = newLearner();
        UUID b = newLearner();
        ratingsController.record(a, new FlashcardRatingRequest("fl_mine", "know", SUBTOPIC_CODE));

        FlashcardReviewScheduleView feedB = scheduleController.schedule(b);
        assertThat(feedB.cards()).isEmpty();
        assertThat(scheduleController.schedule(a).cards()).hasSize(1);
    }

    @Test
    @DisplayName("THE HONESTY PIN, feed edition: the queue serves from the trail while SkillState stays empty — timing only, persisted nowhere")
    void feedNeverProducesMastery() throws InterruptedException {
        UUID learner = newLearner();
        for (int i = 0; i < 4; i++) {
            ratingsController.record(learner, new FlashcardRatingRequest(
                    "fl_pin" + i, i % 2 == 0 ? "know" : "still-learning", SUBTOPIC_CODE));
            pace();
        }

        // the feed serves the trail's timing...
        FlashcardReviewScheduleView feed = scheduleController.schedule(learner);
        assertThat(feed.cards()).hasSize(4);
        assertThat(feed.summary().due() + feed.summary().scheduled()).isEqualTo(4);

        // ...and yet: zero mastery state (ratings are self-report evidence,
        // mastery comes from marked attempts ONLY), and no review_schedules
        // row either — the spec-point queue stays attempts-derived (ADR-031)
        assertThat(skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learner)).isEmpty();

        LearnerStateView state = stateController.state(learner);
        assertThat(state.skillStates()).isEmpty();
        assertThat(state.pendingReviews()).isEmpty();
        assertThat(state.flashcardRatings()).hasSize(4);
    }
}
