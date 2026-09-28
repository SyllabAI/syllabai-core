package com.syllabai.tutor;

import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spec §22 tutor session store (s140): create, append, retrieve — the only
 * sanctioned server-side transcript surface.
 *
 * <p>Ownership is the boundary: every operation resolves the session against
 * the calling learner id, and a foreign session id is indistinguishable from
 * an unknown one (the {@link NotFoundException} carries no signal). The ask
 * path appends the user turn and the assistant turn together after the
 * pipeline completes — a failed ask persists nothing, matching the s139
 * decision that error bubbles are UI chrome, not conversation.</p>
 *
 * <p>s143 conversation management: {@link #list} returns the learner's chats
 * by recency with a derived title (the opening question — sessions stay
 * lifecycle-light, no title column) and a turn count, and {@link #delete}
 * removes a chat and its transcript. Deletion is the learner's data-
 * minimization right (§20): the cross-session memory digest is built from
 * engagement/BKT/review rows, never from these transcripts, so deleting a
 * chat removes exactly the transcript and nothing else.</p>
 */
@Service
public class TutorSessionService {

    private static final Logger log = LoggerFactory.getLogger(TutorSessionService.class);

    /** conversations returned by the list — the ChatGPT-style history pane is
     *  paged by recency in practice, and an unbounded list must never become a
     *  full-transcript dump by accident (the cap applies to SESSIONS, each
     *  contributing one summary row + its seq-1 turn only). */
    static final int MAX_LISTED_SESSIONS = 50;

    /** title = the opening question, single line, bounded for the list pane */
    static final int MAX_TITLE_CHARS = 120;

    private final TutorSessionRepository sessions;
    private final TutorSessionTurnRepository turns;

    public TutorSessionService(TutorSessionRepository sessions, TutorSessionTurnRepository turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    /** View of a stored session (DTO at the API boundary, §22/§23). */
    public record TurnView(int seq, String role, String content, int evidenceCount,
                           boolean refused, String model, String provider,
                           Double latencyMs, Instant at) {
        static TurnView of(TutorSessionTurn turn) {
            return new TurnView(turn.seq(), turn.role(), turn.content(), turn.evidenceCount(),
                    turn.refused(), turn.answerModel(), turn.answerProvider(),
                    turn.latencyMs(), turn.createdAt());
        }
    }

    public record SessionView(UUID sessionId, Instant createdAt, Instant lastActiveAt,
                              List<TurnView> turns) {
    }

    /** One row of the learner's conversation list (s143): identity + recency
     *  + a derived title + how much was said. No transcript content rides the
     *  list — the opening question only, which the learner themselves typed. */
    public record SessionSummaryView(UUID sessionId, Instant createdAt, Instant lastActiveAt,
                                     int turnCount, String title) {
    }

    /** A session id an ask may append to (null = do not persist this ask). */
    public record AppendRequest(UUID sessionId, String question, String answer,
                                int evidenceCount, boolean refused, String model,
                                String provider, Double latencyMs) {
    }

    /** Per-learner open-session cap (R13): POST /tutor/sessions inserts a row
     *  per call with no bound — an authenticated spam vector against the
     *  transcript store. 50 is far above any real usage (a learner chats in
     *  a handful of threads), far below harmful. */
    static final int MAX_SESSIONS_PER_LEARNER = 50;

    /** New empty session for the learner ("New chat" / first ask). */
    @Transactional
    public SessionView create(UUID learnerId) {
        if (sessions.countByLearnerId(learnerId) >= MAX_SESSIONS_PER_LEARNER) {
            throw new ConflictException(
                    "session limit reached (" + MAX_SESSIONS_PER_LEARNER
                            + ") — delete an old chat to start a new one");
        }
        // use the save() RETURN — @PrePersist assigns the id at persist time
        TutorSession session = sessions.save(new TutorSession(learnerId, Instant.now()));
        log.debug("tutor session {} created", session.id());
        return new SessionView(session.id(), session.createdAt(), session.lastActiveAt(), List.of());
    }

    /**
     * §22 retrieval: the caller's own session transcript, seq-ordered. A
     * session owned by another learner 404s exactly like an unknown id.
     */
    @Transactional(readOnly = true)
    public SessionView view(UUID learnerId, UUID sessionId) {
        TutorSession session = owned(learnerId, sessionId);
        List<TurnView> transcript = turns.findBySessionIdOrderBySeq(sessionId).stream()
                .map(TurnView::of)
                .toList();
        return new SessionView(session.id(), session.createdAt(), session.lastActiveAt(), transcript);
    }

    /**
     * The learner's most recent session with its transcript (refresh
     * hydration). Null when the learner has never chatted.
     */
    @Transactional(readOnly = true)
    public SessionView latest(UUID learnerId) {
        return sessions.findFirstByLearnerIdOrderByLastActiveAtDesc(learnerId)
                .map(session -> view(learnerId, session.id()))
                .orElse(null);
    }

    /**
     * The learner's conversations, most recently active first (s143): one
     * summary row per chat — derived title (the opening question) and turn
     * count, never transcript bodies. Empty chats appear honestly with a null
     * title and zero turns; the client renders them as "Empty conversation".
     */
    @Transactional(readOnly = true)
    public List<SessionSummaryView> list(UUID learnerId) {
        List<TutorSession> recent = sessions
                .findByLearnerIdOrderByLastActiveAtDesc(learnerId)
                .stream()
                .limit(MAX_LISTED_SESSIONS)
                .toList();
        if (recent.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = recent.stream().map(TutorSession::id).toList();
        // two batched queries feed every summary — no per-session round trips
        Map<UUID, Long> counts = new HashMap<>();
        for (TutorSessionTurnRepository.SessionTurnCount row : turns.countBySessionIdIn(ids)) {
            counts.put(row.getSessionId(), row.getTurnCount());
        }
        Map<UUID, String> titles = new HashMap<>();
        for (TutorSessionTurn first : turns.findBySessionIdInAndSeq(ids, 1)) {
            // defensive: the append contract makes seq 1 the opening USER
            // turn, but the title must never leak an answer row by accident
            if (ConversationTurn.ROLE_USER.equals(first.role())) {
                titles.put(first.sessionId(), titleOf(first.content()));
            }
        }
        return recent.stream()
                .map(session -> new SessionSummaryView(session.id(), session.createdAt(),
                        session.lastActiveAt(),
                        counts.getOrDefault(session.id(), 0L).intValue(),
                        titles.get(session.id())))
                .toList();
    }

    /**
     * Delete one of the learner's own chats (s143): the transcript rows go
     * first, then the session anchor. A foreign or unknown session id 404s
     * exactly like every other operation. The §22 privacy posture (§20) makes
     * this the learner's right, not an admin action: their transcript, their
     * call. Deleting the latest chat simply hands refresh hydration the next
     * most recent one.
     */
    @Transactional
    public void delete(UUID learnerId, UUID sessionId) {
        TutorSession session = owned(learnerId, sessionId);
        turns.deleteBySessionId(sessionId);
        sessions.delete(session);
        log.debug("tutor session {} deleted with its transcript", sessionId);
    }

    /** Title derivation: the opening question, one line, bounded. */
    private static String titleOf(String content) {
        String oneLine = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_TITLE_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_TITLE_CHARS - 1).stripTrailing() + "…";
    }

    /**
     * Ownership probe for the ask path (s140): a foreign or unknown session id
     * throws before the pipeline runs, so a tampered client fails fast with a
     * 404 instead of spending an LLM call and failing after. A well-formed
     * client only ever holds ids it created, so this is an integrity signal.
     */
    @Transactional(readOnly = true)
    public void requireOwned(UUID learnerId, UUID sessionId) {
        owned(learnerId, sessionId);
    }

    /**
     * Append one exchange (user question + tutor answer) to the learner's
     * session after a completed ask. Foreign/unknown session id ⇒ 404 — the
     * controller treats a missing session on an ask as "do not persist"
     * before calling here, so this throw is a genuine integrity signal.
     */
    @Transactional
    public void append(UUID learnerId, AppendRequest exchange) {
        TutorSession session = owned(learnerId, exchange.sessionId());
        int nextSeq = turns.findTopBySessionIdOrderBySeqDesc(session.id())
                .map(TutorSessionTurn::seq)
                .orElse(0) + 1;
        Instant now = Instant.now();
        turns.save(new TutorSessionTurn(session.id(), nextSeq,
                ConversationTurn.ROLE_USER, bound(exchange.question()), 0, false,
                null, null, null, now));
        turns.save(new TutorSessionTurn(session.id(), nextSeq + 1,
                ConversationTurn.ROLE_ASSISTANT,
                bound(ConversationTurn.stripCitationMarkers(exchange.answer())),
                exchange.evidenceCount(), exchange.refused(), exchange.model(),
                exchange.provider(), exchange.latencyMs(), now));
        session.markActive(now);
        log.debug("tutor session {} appended exchange at seq {}", session.id(), nextSeq);
    }

    private TutorSession owned(UUID learnerId, UUID sessionId) {
        return sessions.findByIdAndLearnerId(sessionId, learnerId)
                .orElseThrow(() -> new NotFoundException("tutor session", sessionId));
    }

    /** stored transcript bound — prompts cap turns at 2000; transcripts get air */
    private static String bound(String content) {
        String safe = content == null ? "" : content.strip();
        return safe.length() <= TutorSessionTurn.MAX_CONTENT_CHARS
                ? safe
                : safe.substring(0, TutorSessionTurn.MAX_CONTENT_CHARS) + "…";
    }
}
