package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.FlashcardRatingController;
import com.syllabai.learner.FlashcardRatingController.FlashcardRatingRequest;
import com.syllabai.learner.FlashcardRatingRepository;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.FlashcardRatingView;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import java.util.List;
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
 * Integration test: the flashcard rating evidence class (V47, ADR-029
 * tranche 4.4) against a real Postgres. Pins the whole contract end to end:
 *
 * <ul>
 *   <li>a hub rating on a real subtopic anchor persists and surfaces in the
 *       learner-state view (newest first);</li>
 *   <li>append-only semantics — a re-rate is a new row and the newest one is
 *       the card's current rating;</li>
 *   <li>FAIL-CLOSED ATTRIBUTION — an unknown anchor, or a code that resolves
 *       to a non-SUBTOPIC node, is a 404 and persists NOTHING;</li>
 *   <li>THE HONESTY PIN — ratings never produce mastery: SkillStateRepository
 *       stays empty after ratings, and the state view's skillStates stay
 *       empty while flashcardRatings carry the evidence.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class FlashcardRatingFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SUBTOPIC_CODE = "4CH1-S1-a";
    private static final String SUBJECT_CODE = "4CH1";
    private static UUID subtopicNodeId;

    @Autowired
    private AuthService authService;
    @Autowired
    private FlashcardRatingController ratingsController;
    @Autowired
    private FlashcardRatingRepository ratingRows;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        KnowledgeNode subtopic = knowledgeNodes.save(new KnowledgeNode(
                SUBTOPIC_CODE, NodeType.SUBTOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                SUBJECT_CODE, NodeType.SUBJECT, "Chemistry (IGCSE)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        subtopicNodeId = subtopic.id();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-fcr-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("rating persists on a resolved subtopic anchor, surfaces newest-first in /state, and re-rating appends")
    void happyPathAppendAndState() {
        UUID learner = newLearner();

        FlashcardRatingView first = ratingsController.record(learner,
                new FlashcardRatingRequest("fl_testCard1", "know", SUBTOPIC_CODE));
        assertThat(first.cardId()).isEqualTo("fl_testCard1");
        assertThat(first.rating()).isEqualTo("know");
        assertThat(first.subtopicCode()).isEqualTo(SUBTOPIC_CODE);
        assertThat(first.nodeId()).isEqualTo(subtopicNodeId);
        assertThat(first.occurredAt()).isNotNull();

        // the hub's lowercase wire form parses to the same canonical rating
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_testCard2", "still-learning", SUBTOPIC_CODE));

        // re-rate card 1 as still-learning: append-only — a NEW row, newest wins
        FlashcardRatingView rerate = ratingsController.record(learner,
                new FlashcardRatingRequest("fl_testCard1", "still-learning", SUBTOPIC_CODE));
        assertThat(rerate.rating()).isEqualTo("still-learning");
        assertThat(ratingRows.count()).isEqualTo(3);

        LearnerStateView state = stateController.state(learner);
        assertThat(state.flashcardRatings()).hasSize(3);
        // newest first — the re-rate leads
        assertThat(state.flashcardRatings().get(0).rating()).isEqualTo("still-learning");
        assertThat(state.flashcardRatings().get(0).cardId()).isEqualTo("fl_testCard1");
        assertThat(state.flashcardRatings().get(2).cardId()).isEqualTo("fl_testCard2");

        // the current rating of card 1 = the LATEST event, not the first
        List<FlashcardRatingView> card1 = state.flashcardRatings().stream()
                .filter(r -> r.cardId().equals("fl_testCard1"))
                .toList();
        assertThat(card1).hasSize(2);
        assertThat(card1.get(0).rating()).isEqualTo("still-learning");
    }

    @Test
    @DisplayName("fail-closed attribution: unknown anchor and non-subtopic anchor are 404 and persist nothing")
    void failClosedAttribution() {
        UUID learner = newLearner();
        long before = ratingRows.count();

        assertThatThrownBy(() -> ratingsController.record(learner,
                new FlashcardRatingRequest("fl_x1", "know", "4CH1-S99-z")))
                .isInstanceOf(NotFoundException.class);
        // a real node that is NOT a subtopic (the subject root) is equally refused
        assertThatThrownBy(() -> ratingsController.record(learner,
                new FlashcardRatingRequest("fl_x2", "know", SUBJECT_CODE)))
                .isInstanceOf(NotFoundException.class);

        assertThat(ratingRows.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("bad rating vocabulary is a 400")
    void badRatingVocabulary() {
        UUID learner = newLearner();
        assertThatThrownBy(() -> ratingsController.record(learner,
                new FlashcardRatingRequest("fl_x3", "banana", SUBTOPIC_CODE)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("THE HONESTY PIN: ratings are exposure evidence only — no SkillState row is ever created")
    void ratingsNeverProduceMastery() {
        UUID learner = newLearner();
        for (int i = 0; i < 5; i++) {
            ratingsController.record(learner, new FlashcardRatingRequest(
                    "fl_mastery" + i, i % 2 == 0 ? "know" : "still-learning", SUBTOPIC_CODE));
        }
        assertThat(ratingRows.findByLearnerIdOrderByOccurredAtDesc(learner,
                org.springframework.data.domain.PageRequest.of(0, 50))).hasSize(5);
        // five "know"/"still-learning" events and yet: zero mastery state.
        // Mastery comes from marked attempts ONLY (the learner-model honesty rule).
        assertThat(skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learner)).isEmpty();

        LearnerStateView state = stateController.state(learner);
        assertThat(state.skillStates()).isEmpty();
        assertThat(state.flashcardRatings()).hasSize(5);
    }

    @Test
    @DisplayName("learner isolation: another learner's ratings never appear in my state")
    void learnerIsolation() {
        UUID a = newLearner();
        UUID b = newLearner();
        ratingsController.record(a, new FlashcardRatingRequest("fl_mine1", "know", SUBTOPIC_CODE));

        LearnerStateView stateB = stateController.state(b);
        assertThat(stateB.flashcardRatings()).isEmpty();
        assertThat(stateController.state(a).flashcardRatings()).hasSize(1);
    }
}
