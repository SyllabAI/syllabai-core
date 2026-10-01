package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.ConflictException;
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
    @DisplayName("create is capped per learner (R13: POST /sessions was an "
            + "unbounded row-spam vector) — conflict at the limit, no row written")
    void createCappedPerLearner() {
        when(sessions.countByLearnerId(learner))
                .thenReturn((long) TutorSessionService.MAX_SESSIONS_PER_LEARNER);

        assertThatThrownBy(() -> service.create(learner))
                .isInstanceOf(ConflictException.class);
        verify(sessions, never()).save(any());
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

    // ── s143 conversation management: list + delete ──────────────────────────

    /** minimal fake for the grouped-count projection */
    private static TutorSessionTurnRepository.SessionTurnCount countOf(UUID sessionId, long n) {
        return new TutorSessionTurnRepository.SessionTurnCount() {
            @Override public UUID getSessionId() { return sessionId; }
            @Override public long getTurnCount() { return n; }
        };
    }

    @Test
    @DisplayName("list returns summaries by recency: opening question as title, joined turn count")
    void listSummaries() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        // repository order IS the recency order the service must preserve
        when(sessions.findByLearnerIdOrderByLastActiveAtDesc(learner))
                .thenReturn(List.of(ownedSession(second, learner), ownedSession(first, learner)));
        when(turns.countBySessionIdIn(any()))
                .thenReturn(List.of(countOf(second, 6L), countOf(first, 2L)));
        when(turns.findBySessionIdInAndSeq(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of(
                        new TutorSessionTurn(second, 1, ConversationTurn.ROLE_USER,
                                "How do I balance a redox half-equation?", 0, false,
                                null, null, null, Instant.now()),
                        // multi-line opening question collapses to one title line
                        new TutorSessionTurn(first, 1, ConversationTurn.ROLE_USER,
                                "what   is\n\na mole?", 0, false,
                                null, null, null, Instant.now())));

        List<TutorSessionService.SessionSummaryView> list = service.list(learner);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).sessionId()).isEqualTo(second);
        assertThat(list.get(0).title()).isEqualTo("How do I balance a redox half-equation?");
        assertThat(list.get(0).turnCount()).isEqualTo(6);
        assertThat(list.get(1).sessionId()).isEqualTo(first);
        assertThat(list.get(1).title()).isEqualTo("what is a mole?");
        assertThat(list.get(1).turnCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("list caps at MAX_LISTED_SESSIONS and never queries beyond the page")
    void listCapped() {
        List<TutorSession> many = new java.util.ArrayList<>();
        for (int i = 0; i < TutorSessionService.MAX_LISTED_SESSIONS + 5; i++) {
            many.add(ownedSession(UUID.randomUUID(), learner));
        }
        when(sessions.findByLearnerIdOrderByLastActiveAtDesc(learner)).thenReturn(many);
        when(turns.countBySessionIdIn(any())).thenReturn(List.of());
        when(turns.findBySessionIdInAndSeq(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of());

        List<TutorSessionService.SessionSummaryView> list = service.list(learner);

        assertThat(list).hasSize(TutorSessionService.MAX_LISTED_SESSIONS);
        // the page fed to the batched queries is the capped one — the list
        // must never become a transcript dump by accident
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(turns).countBySessionIdIn(ids.capture());
        assertThat(ids.getValue()).hasSize(TutorSessionService.MAX_LISTED_SESSIONS);
        assertThat(ids.getValue()).containsExactlyElementsOf(
                many.stream().limit(TutorSessionService.MAX_LISTED_SESSIONS)
                        .map(TutorSession::id).toList());
    }

    @Test
    @DisplayName("list titles stay honest: long questions ellipsize, non-user seq-1 rows never title, empty chats have no title")
    void listTitleHonesty() {
        UUID chatty = UUID.randomUUID();
        UUID defensive = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        when(sessions.findByLearnerIdOrderByLastActiveAtDesc(learner))
                .thenReturn(List.of(ownedSession(chatty, learner),
                        ownedSession(defensive, learner), ownedSession(empty, learner)));
        when(turns.countBySessionIdIn(any())).thenReturn(List.of(
                countOf(chatty, 4L), countOf(defensive, 2L)));
        when(turns.findBySessionIdInAndSeq(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of(
                        new TutorSessionTurn(chatty, 1, ConversationTurn.ROLE_USER,
                                "x".repeat(500), 0, false, null, null, null, Instant.now()),
                        // a defensive case: seq 1 exists but is NOT a user turn —
                        // no title rather than a leaked answer row
                        new TutorSessionTurn(defensive, 1, ConversationTurn.ROLE_ASSISTANT,
                                "answer never titles the chat", 0, false, null, null, null,
                                Instant.now())));

        List<TutorSessionService.SessionSummaryView> list = service.list(learner);

        assertThat(list.get(0).title()).hasSize(TutorSessionService.MAX_TITLE_CHARS);
        assertThat(list.get(0).title()).endsWith("…");
        assertThat(list.get(1).title()).isNull();
        assertThat(list.get(2).title()).isNull();
        assertThat(list.get(2).turnCount()).isZero();
    }

    @Test
    @DisplayName("a learner with no chats gets an empty list, not null")
    void listEmpty() {
        when(sessions.findByLearnerIdOrderByLastActiveAtDesc(learner)).thenReturn(List.of());
        assertThat(service.list(learner)).isEmpty();
        verify(turns, never()).countBySessionIdIn(any());
    }

    @Test
    @DisplayName("delete removes the transcript rows then the session anchor")
    void deleteRemovesTranscriptThenSession() {
        UUID id = UUID.randomUUID();
        TutorSession session = ownedSession(id, learner);
        when(sessions.findByIdAndLearnerId(id, learner)).thenReturn(Optional.of(session));

        service.delete(learner, id);

        var inOrder = org.mockito.Mockito.inOrder(turns, sessions);
        inOrder.verify(turns).deleteBySessionId(id);
        inOrder.verify(sessions).delete(session);
    }

    @Test
    @DisplayName("a foreign delete 404s exactly like an unknown id and removes nothing")
    void deleteForeignIndistinguishable() {
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(sessions.findByIdAndLearnerId(foreign, learner)).thenReturn(Optional.empty());
        when(sessions.findByIdAndLearnerId(unknown, learner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(learner, foreign))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(learner, unknown))
                .isInstanceOf(NotFoundException.class);
        verify(turns, never()).deleteBySessionId(any());
        verify(sessions, never()).delete(any(TutorSession.class));
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

    @Test
    @DisplayName("V53 write-once: the first ref-carrying append fixes the session's course; later ref-less appends never erase it")
    void firstRefCarryingAppendWritesCourseRefOnce() {
        UUID id = UUID.randomUUID();
        TutorSession session = ownedSession(id, learner);
        when(sessions.findByIdAndLearnerId(id, learner)).thenReturn(Optional.of(session));
        when(turns.findTopBySessionIdOrderBySeqDesc(id)).thenReturn(Optional.empty());

        service.append(learner, new TutorSessionService.AppendRequest(
                id, "q", "a", 1, false, null, null, null, "4CH1-2017"));
        assertThat(session.courseRef()).isEqualTo("4CH1-2017");

        // a later ref-less (legacy) append neither erases nor rewrites the ref
        service.append(learner, new TutorSessionService.AppendRequest(
                id, "q2", "a2", 1, false, null, null, null));
        assertThat(session.courseRef()).isEqualTo("4CH1-2017");

        // the same ref again is a no-op, not a second write or a conflict
        service.append(learner, new TutorSessionService.AppendRequest(
                id, "q3", "a3", 1, false, null, null, null, "4CH1-2017"));
        assertThat(session.courseRef()).isEqualTo("4CH1-2017");
    }

    @Test
    @DisplayName("V53 integrity: an append naming a DIFFERENT course is a 409 conflict, never a silent scope switch")
    void mismatchedCourseRefIsAConflict() {
        UUID id = UUID.randomUUID();
        TutorSession session = ownedSession(id, learner);
        when(sessions.findByIdAndLearnerId(id, learner)).thenReturn(Optional.of(session));
        when(turns.findTopBySessionIdOrderBySeqDesc(id)).thenReturn(Optional.empty());

        service.append(learner, new TutorSessionService.AppendRequest(
                id, "q", "a", 1, false, null, null, null, "4CH1-2017"));

        assertThatThrownBy(() -> service.append(learner, new TutorSessionService.AppendRequest(
                id, "physics question", "would-be answer", 1, false, null, null, null,
                "4PH1-2017")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("4CH1-2017")
                .hasMessageContaining("4PH1-2017");

        // the refused append persisted NOTHING (only the first exchange's two
        // turns) and the session kept its scope
        verify(turns, org.mockito.Mockito.times(2)).save(any());
        assertThat(session.courseRef()).isEqualTo("4CH1-2017");
    }

    @Test
    @DisplayName("V53 views: SessionView and the list summaries expose courseRef so the hub shows what was actually served")
    void viewsCarryCourseRef() {
        UUID withCourse = UUID.randomUUID();
        UUID courseless = UUID.randomUUID();
        TutorSession scoped = ownedSession(withCourse, learner);
        scoped.attachCourse("4CH1-2017");
        TutorSession legacy = ownedSession(courseless, learner);
        when(sessions.findByIdAndLearnerId(withCourse, learner)).thenReturn(Optional.of(scoped));
        when(turns.findBySessionIdOrderBySeq(withCourse)).thenReturn(List.of());

        TutorSessionService.SessionView view = service.view(learner, withCourse);
        assertThat(view.courseRef()).isEqualTo("4CH1-2017");

        when(sessions.findByLearnerIdOrderByLastActiveAtDesc(learner))
                .thenReturn(List.of(scoped, legacy));
        when(turns.countBySessionIdIn(any())).thenReturn(List.of());
        when(turns.findBySessionIdInAndSeq(any(), org.mockito.ArgumentMatchers.eq(1)))
                .thenReturn(List.of());

        List<TutorSessionService.SessionSummaryView> list = service.list(learner);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).courseRef()).isEqualTo("4CH1-2017");
        // NULL is legitimate history: the pre-V53 chat shows honestly as course-less
        assertThat(list.get(1).courseRef()).isNull();

        // and the probe: a consistent ref passes silently, a different one conflicts
        service.requireCourseConsistent(learner, withCourse, "4CH1-2017");
        assertThatThrownBy(() -> service.requireCourseConsistent(learner, withCourse, "4PH1-2017"))
                .isInstanceOf(ConflictException.class);
        // a course-less session accepts any ref (the first one wins at append);
        // the courseless session must be resolvable for the probe to reach the check
        when(sessions.findByIdAndLearnerId(courseless, learner)).thenReturn(Optional.of(legacy));
        service.requireCourseConsistent(learner, courseless, "4PH1-2017");
    }
}
