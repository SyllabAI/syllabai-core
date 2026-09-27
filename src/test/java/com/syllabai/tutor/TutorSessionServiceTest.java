package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * §22 tutor session store (s140): create → append → view lifecycle, plus the
 * ownership boundary — a session another learner owns is indistinguishable
 * from an unknown id on EVERY operation (view, append, requireOwned), and
 * the ask-path probe fails before the pipeline spends anything.
 */
class TutorSessionServiceTest {

    private final TutorSessionRepository sessions = mock(TutorSessionRepository.class);
    private final TutorSessionTurnRepository turns = mock(TutorSessionTurnRepository.class);
    private final TutorSessionService service = new TutorSessionService(sessions, turns);

    private final UUID learner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    private TutorSession ownedSession(UUID id, UUID owner) {
        TutorSession session = new TutorSession(owner, Instant.now().minusSeconds(3600));
        // id is @PrePersist-assigned; simulate persistence
        try {
            var field = TutorSession.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(session, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return session;
    }

    @Test
    @DisplayName("create returns an empty session view for the learner")
    void create() {
        TutorSession saved = ownedSession(UUID.randomUUID(), learner);
        when(sessions.save(any())).thenReturn(saved);

        TutorSessionService.SessionView view = service.create(learner);

        assertThat(view.sessionId()).isEqualTo(saved.id());
        assertThat(view.turns()).isEmpty();
        assertThat(view.lastActiveAt()).isEqualTo(saved.createdAt());
    }

    @Test
    @DisplayName("view returns the caller's own transcript, seq-ordered")
    void viewOwnTranscript() {
        UUID id = UUID.randomUUID();
        when(sessions.findByIdAndLearnerId(id, learner))
                .thenReturn(Optional.of(ownedSession(id, learner)));
        when(turns.findBySessionIdOrderBySeq(id)).thenReturn(List.of(
                new TutorSessionTurn(id, 1, ConversationTurn.ROLE_USER, "what is a mole?", 0,
                        false, null, null, null, Instant.now()),
                new TutorSessionTurn(id, 2, ConversationTurn.ROLE_ASSISTANT,
                        "A mole is an amount of substance.", 4, false, "m", "p", 900.0,
                        Instant.now())));

        TutorSessionService.SessionView view = service.view(learner, id);

        assertThat(view.turns()).hasSize(2);
        assertThat(view.turns().get(0).role()).isEqualTo(ConversationTurn.ROLE_USER);
        assertThat(view.turns().get(1).seq()).isEqualTo(2);
        assertThat(view.turns().get(1).provider()).isEqualTo("p");
    }

    @Test
    @DisplayName("a foreign session id 404s exactly like an unknown id (view, append, probe)")
    void foreignSessionIndistinguishable() {
        UUID foreign = UUID.randomUUID();
        when(sessions.findByIdAndLearnerId(foreign, learner)).thenReturn(Optional.empty());
        UUID unknown = UUID.randomUUID();
        when(sessions.findByIdAndLearnerId(unknown, learner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(learner, foreign))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.view(learner, unknown))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.requireOwned(learner, foreign))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.append(learner, new TutorSessionService.AppendRequest(
                foreign, "q", "a", 0, false, null, null, null)))
                .isInstanceOf(NotFoundException.class);
        // nothing was ever written for the foreign id
        verify(turns, never()).save(any());
    }

    @Test
    @DisplayName("append writes the user + assistant pair at the next seq and refreshes recency")
    void appendWritesPair() {
        UUID id = UUID.randomUUID();
        TutorSession session = ownedSession(id, learner);
        when(sessions.findByIdAndLearnerId(id, learner)).thenReturn(Optional.of(session));
        when(turns.findTopBySessionIdOrderBySeqDesc(id)).thenReturn(Optional.of(
                new TutorSessionTurn(id, 4, ConversationTurn.ROLE_ASSISTANT, "prior", 0,
                        false, null, null, null, Instant.now())));

        service.append(learner, new TutorSessionService.AppendRequest(
                id, "what is a mole [2]?", "Twelve grams of carbon [1].", 3, false,
                "llama", "groq", 750.0));

        ArgumentCaptor<TutorSessionTurn> captor = ArgumentCaptor.forClass(TutorSessionTurn.class);
        verify(turns, org.mockito.Mockito.times(2)).save(captor.capture());
        List<TutorSessionTurn> written = captor.getAllValues();
        assertThat(written.get(0).seq()).isEqualTo(5);
        assertThat(written.get(0).role()).isEqualTo(ConversationTurn.ROLE_USER);
        // the user turn keeps its text verbatim — [2] is quoting, not citing
        assertThat(written.get(0).content()).isEqualTo("what is a mole [2]?");
        assertThat(written.get(1).seq()).isEqualTo(6);
        assertThat(written.get(1).role()).isEqualTo(ConversationTurn.ROLE_ASSISTANT);
        // the stored answer is the learner-visible prose: markers (and their
        // leading whitespace) stripped
        assertThat(written.get(1).content()).isEqualTo("Twelve grams of carbon.");
        assertThat(written.get(1).evidenceCount()).isEqualTo(3);
        assertThat(written.get(1).answerModel()).isEqualTo("llama");
        assertThat(written.get(1).latencyMs()).isEqualTo(750.0);
        // recency follows the append
        assertThat(session.lastActiveAt()).isAfter(session.createdAt());
    }

    @Test
    @DisplayName("latest resolves the learner's most recent session; null when they never chatted")
    void latestResolution() {
        when(sessions.findFirstByLearnerIdOrderByLastActiveAtDesc(learner))
                .thenReturn(Optional.empty());
        assertThat(service.latest(learner)).isNull();

        UUID id = UUID.randomUUID();
        when(sessions.findFirstByLearnerIdOrderByLastActiveAtDesc(other))
                .thenReturn(Optional.of(ownedSession(id, other)));
        when(sessions.findByIdAndLearnerId(id, other))
                .thenReturn(Optional.of(ownedSession(id, other)));
        when(turns.findBySessionIdOrderBySeq(id)).thenReturn(List.of());
        assertThat(service.latest(other)).isNotNull();
    }

    @Test
    @DisplayName("stored turn content is bounded (4000 chars) — hostile or runaway answers cannot bloat rows")
    void contentBounded() {
        UUID id = UUID.randomUUID();
        when(sessions.findByIdAndLearnerId(id, learner))
                .thenReturn(Optional.of(ownedSession(id, learner)));
        when(turns.findTopBySessionIdOrderBySeqDesc(id)).thenReturn(Optional.empty());

        service.append(learner, new TutorSessionService.AppendRequest(
                id, "q", "a".repeat(9000), 0, false, null, null, null));

        ArgumentCaptor<TutorSessionTurn> captor = ArgumentCaptor.forClass(TutorSessionTurn.class);
        verify(turns, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).content().length())
                .isLessThanOrEqualTo(TutorSessionTurn.MAX_CONTENT_CHARS + 1);
    }
}
