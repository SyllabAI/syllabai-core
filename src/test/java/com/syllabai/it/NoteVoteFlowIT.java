package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.LearnerStateController;
import com.syllabai.learner.NoteVoteController;
import com.syllabai.learner.NoteVoteController.NoteVoteRequest;
import com.syllabai.learner.NoteVoteRepository;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.LearnerStateView;
import com.syllabai.learner.dto.NoteVoteView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the note-vote evidence class (V48, ADR-029 tranche 4.9)
 * against a real Postgres. Pins the whole contract end to end:
 *
 * <ul>
 *   <li>a hub vote on a real note anchor persists and surfaces in the
 *       learner-state view (newest first);</li>
 *   <li>append-only semantics — a vote CHANGE is a new row and the newest
 *       one is the note's current vote (the trail keeps the change);</li>
 *   <li>FAIL-CLOSED ATTRIBUTION — an unknown anchor, the subject root and a
 *       semantic-layer node are all 404 and persist NOTHING;</li>
 *   <li>THE HONESTY PIN — votes never produce mastery:
 *       SkillStateRepository stays empty after votes, and the state view's
 *       skillStates stay empty while noteVotes carry the evidence.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class NoteVoteFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String SUBTOPIC_CODE = "4CH1-S1-a";
    private static final String SUBJECT_CODE = "4CH1";
    private static final String CONCEPT_CODE = "4CH1-CON-IT-FIXTURE";

    @Autowired
    private AuthService authService;
    @Autowired
    private NoteVoteController votesController;
    @Autowired
    private NoteVoteRepository voteRows;
    @Autowired
    private LearnerStateController stateController;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        // mirrors the production 4CH1 graph's level naming: the anchor level
        // ("4CH1-S1-a") ingests as TOPIC, the spec statements as SUBTOPIC —
        // the structural gate must accept both (same ruling as V47 ratings)
        knowledgeNodes.save(new KnowledgeNode(
                SUBTOPIC_CODE, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                SUBJECT_CODE, NodeType.SUBJECT, "Chemistry (IGCSE)", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        knowledgeNodes.save(new KnowledgeNode(
                CONCEPT_CODE, NodeType.CONCEPT, "IT concept fixture", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-nv-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** learner-scoped row count — tests share one DB, so a global count() is
     *  ordering-sensitive and would be a flaky assertion. */
    private long rowsOf(UUID learner) {
        return voteRows.findByLearnerIdOrderByOccurredAtDesc(learner, Pageable.unpaged()).size();
    }

    @Test
    @DisplayName("vote persists on a resolved note anchor, surfaces newest-first in /state, and a vote change appends")
    void happyPathAppendAndState() throws InterruptedException {
        UUID learner = newLearner();

        NoteVoteView first = votesController.record(learner,
                new NoteVoteRequest("rn_testNote1", "helpful", SUBTOPIC_CODE));
        Thread.sleep(60);
        votesController.record(learner,
                new NoteVoteRequest("rn_testNote2", "down", SUBTOPIC_CODE));
        Thread.sleep(60);

        // change the vote on note 1: append-only — a NEW row, newest wins;
        // the hub's local "down" form maps onto the canonical NOT_HELPFUL
        NoteVoteView changed = votesController.record(learner,
                new NoteVoteRequest("rn_testNote1", "down", SUBTOPIC_CODE));
        assertThat(first.vote()).isEqualTo("helpful");
        assertThat(changed.vote()).isEqualTo("not-helpful");
        assertThat(rowsOf(learner)).isEqualTo(3);

        LearnerStateView state = stateController.state(learner);
        assertThat(state.noteVotes()).hasSize(3);
        // newest first: [note1 change (not-helpful), note2 (not-helpful), note1 first (helpful)]
        assertThat(state.noteVotes().get(0).noteId()).isEqualTo("rn_testNote1");
        assertThat(state.noteVotes().get(0).vote()).isEqualTo("not-helpful");
        assertThat(state.noteVotes().get(1).noteId()).isEqualTo("rn_testNote2");
        assertThat(state.noteVotes().get(2).noteId()).isEqualTo("rn_testNote1");
        assertThat(state.noteVotes().get(2).vote()).isEqualTo("helpful");

        // the current vote of note 1 = the LATEST event, not the first
        var note1 = state.noteVotes().stream()
                .filter(v -> v.noteId().equals("rn_testNote1"))
                .toList();
        assertThat(note1).hasSize(2);
        assertThat(note1.get(0).vote()).isEqualTo("not-helpful");
    }

    @Test
    @DisplayName("fail-closed attribution: unknown anchor, subject root and semantic-layer nodes are 404 and persist nothing")
    void failClosedAttribution() {
        UUID learner = newLearner();
        long before = rowsOf(learner);

        assertThatThrownBy(() -> votesController.record(learner,
                new NoteVoteRequest("rn_x1", "helpful", "4CH1-S99-z")))
                .isInstanceOf(NotFoundException.class);
        // a real node that is NOT below the subject root (the subject itself)
        assertThatThrownBy(() -> votesController.record(learner,
                new NoteVoteRequest("rn_x2", "helpful", SUBJECT_CODE)))
                .isInstanceOf(NotFoundException.class);
        // the semantic layer never takes vote attribution
        assertThatThrownBy(() -> votesController.record(learner,
                new NoteVoteRequest("rn_x3", "helpful", CONCEPT_CODE)))
                .isInstanceOf(NotFoundException.class);

        assertThat(rowsOf(learner)).isEqualTo(before);
    }

    @Test
    @DisplayName("bad vote vocabulary is a 400")
    void badVoteVocabulary() {
        UUID learner = newLearner();
        assertThatThrownBy(() -> votesController.record(learner,
                new NoteVoteRequest("rn_x3", "meh", SUBTOPIC_CODE)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("THE HONESTY PIN: votes are exposure evidence only — no SkillState row is ever created")
    void votesNeverProduceMastery() {
        UUID learner = newLearner();
        for (int i = 0; i < 5; i++) {
            votesController.record(learner, new NoteVoteRequest(
                    "rn_mastery" + i, i % 2 == 0 ? "up" : "down", SUBTOPIC_CODE));
        }
        assertThat(voteRows.findByLearnerIdOrderByOccurredAtDesc(learner,
                org.springframework.data.domain.PageRequest.of(0, 50))).hasSize(5);
        // five vote events and yet: zero mastery state. Mastery comes from
        // marked attempts ONLY (the learner-model honesty rule).
        assertThat(skillStates.findByLearnerIdOrderByLastPracticedAtDesc(learner)).isEmpty();

        LearnerStateView state = stateController.state(learner);
        assertThat(state.skillStates()).isEmpty();
        assertThat(state.noteVotes()).hasSize(5);
        // ...and the ratings slice is untouched by vote traffic
        assertThat(state.flashcardRatings()).isEmpty();
    }

    @Test
    @DisplayName("learner isolation: another learner's votes never appear in my state")
    void learnerIsolation() {
        UUID a = newLearner();
        UUID b = newLearner();
        votesController.record(a, new NoteVoteRequest("rn_mine1", "up", SUBTOPIC_CODE));

        LearnerStateView stateB = stateController.state(b);
        assertThat(stateB.noteVotes()).isEmpty();
        assertThat(stateController.state(a).noteVotes()).hasSize(1);
    }
}
