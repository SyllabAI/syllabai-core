package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.shared.NotFoundException;
import com.syllabai.tutor.TutorController;
import com.syllabai.tutor.TutorSessionService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (session-141): the §22 tutor session store against the
 * REAL Flyway schema — the incident class the mock-based service tests
 * cannot see.
 *
 * <p>The 2026-09-27 incident: V42 declared {@code role VARCHAR(8)} while
 * {@code 'assistant'} is nine characters. Every session-anchored ask
 * inserted the user turn, failed the assistant turn with "value too long",
 * rolled the append back and 500'd the ask after the LLM answer had been
 * generated — learners saw "AI answers are temporarily unavailable" on
 * every tutor-chat ask. {@code TutorSessionServiceTest} mocks both
 * repositories, so no test ever inserted a real row against the migrated
 * schema; this IT does, through the real controller path.</p>
 *
 * <p>No curriculum is seeded and no generator stub is installed: with zero
 * ACTIVE curriculum versions the scope resolver returns empty and the ask
 * deterministically REFUSES (no LLM call) — and a refused exchange still
 * appends both turns, which is exactly the path that was broken.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class TutorSessionStoreFlowIT {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private AuthService authService;

    @Autowired
    private TutorController tutor;

    @Autowired
    private TutorSessionService sessionStore;

    private UUID registerLearner() {
        return authService.register(new RegisterRequest(
                "session-store-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "Session Store IT")).user().id();
    }

    @Test
    @DisplayName("session-anchored ask persists both turns against the real schema")
    void sessionAnchoredAskPersistsBothTurns() {
        UUID learnerId = registerLearner();
        var session = sessionStore.create(learnerId);

        // direct controller call: ownership probe -> pipeline -> append.
        // Fresh container: no ACTIVE curriculum -> deterministic refusal,
        // no LLM spend. A refused exchange still appends both turns.
        var answer = tutor.ask(learnerId, new TutorController.TutorAskRequest(
                "What is a covalent bond?", null, session.sessionId()));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.provider()).isEqualTo("deterministic-refusal");

        // THE regression assertion: the assistant turn ('assistant' = 9
        // chars) must persist — the varchar(8) incident rolled both turns
        // back and surfaced as a 500 after the answer was generated.
        var view = sessionStore.view(learnerId, session.sessionId());
        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(0).seq()).isEqualTo(1);
        assertThat(view.turns().get(0).role()).isEqualTo("user");
        assertThat(view.turns().get(0).content()).isEqualTo("What is a covalent bond?");
        assertThat(view.turns().get(1).seq()).isEqualTo(2);
        assertThat(view.turns().get(1).role()).isEqualTo("assistant");
        assertThat(view.turns().get(1).refused()).isTrue();
        assertThat(view.turns().get(1).model()).isNull();
        assertThat(view.turns().get(1).provider()).isEqualTo("deterministic-refusal");
        assertThat(view.turns().get(1).content())
                .contains("can't answer that from the validated course material");

        // recency bumped: hydration (/latest) must find this session
        assertThat(view.lastActiveAt()).isAfterOrEqualTo(view.createdAt());
        assertThat(sessionStore.latest(learnerId).sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    @DisplayName("a foreign session id fails the ask before the pipeline (integrity probe)")
    void foreignSessionIdFailsAskFast() {
        UUID learner = registerLearner();
        UUID other = registerLearner();
        var foreign = sessionStore.create(other);

        // the probe throws BEFORE the pipeline: no LLM call, no turns
        // written to either learner's session
        assertThatThrownBy(() -> tutor.ask(learner, new TutorController.TutorAskRequest(
                "What is a covalent bond?", null, foreign.sessionId())))
                .isInstanceOf(NotFoundException.class);

        assertThat(sessionStore.view(other, foreign.sessionId()).turns()).isEmpty();
        assertThat(sessionStore.latest(learner)).isNull();
    }

    @Test
    @DisplayName("conversation list + delete (s143): summaries by recency, delete cascades the transcript")
    void conversationListAndDelete() {
        UUID learner = registerLearner();
        var older = sessionStore.create(learner);
        var newer = sessionStore.create(learner);

        // append one exchange to each through the real ask path — the
        // deterministic refusal appends both turns, no LLM spend
        tutor.ask(learner, new TutorController.TutorAskRequest(
                "What is a covalent bond?", null, older.sessionId()));
        tutor.ask(learner, new TutorController.TutorAskRequest(
                "How do I balance a redox half-equation?", null, newer.sessionId()));

        var list = sessionStore.list(learner);
        assertThat(list).hasSize(2);
        // most recently active first; titles are the opening questions;
        // counts are the stored turn rows (user + assistant)
        assertThat(list.get(0).sessionId()).isEqualTo(newer.sessionId());
        assertThat(list.get(0).title()).isEqualTo("How do I balance a redox half-equation?");
        assertThat(list.get(0).turnCount()).isEqualTo(2);
        assertThat(list.get(1).sessionId()).isEqualTo(older.sessionId());
        assertThat(list.get(1).title()).isEqualTo("What is a covalent bond?");
        assertThat(list.get(1).turnCount()).isEqualTo(2);

        // ownership: another learner's list never includes these rows
        UUID other = registerLearner();
        assertThat(sessionStore.list(other)).isEmpty();

        // delete the newer chat: the anchor AND its transcript rows go
        sessionStore.delete(learner, newer.sessionId());
        var after = sessionStore.list(learner);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).sessionId()).isEqualTo(older.sessionId());
        assertThatThrownBy(() -> sessionStore.view(learner, newer.sessionId()))
                .isInstanceOf(NotFoundException.class);

        // a foreign delete is indistinguishable from an unknown id and
        // removes nothing from the owner
        assertThatThrownBy(() -> sessionStore.delete(other, older.sessionId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(sessionStore.view(learner, older.sessionId()).turns()).hasSize(2);
    }
}
