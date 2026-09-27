package com.syllabai.tutor;

import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.List;
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
 */
@Service
public class TutorSessionService {

    private static final Logger log = LoggerFactory.getLogger(TutorSessionService.class);

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

    /** A session id an ask may append to (null = do not persist this ask). */
    public record AppendRequest(UUID sessionId, String question, String answer,
                                int evidenceCount, boolean refused, String model,
                                String provider, Double latencyMs) {
    }

    /** New empty session for the learner ("New chat" / first ask). */
    @Transactional
    public SessionView create(UUID learnerId) {
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
