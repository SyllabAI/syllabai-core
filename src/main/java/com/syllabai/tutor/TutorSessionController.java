package com.syllabai.tutor;

import com.syllabai.identity.CurrentUserId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spec §22 tutor session endpoints (s140): the sanctioned server-side
 * transcript surface the s139 working-memory design deferred to.
 *
 * <ul>
 *   <li>{@code POST   /api/v1/tutor/sessions} — start a chat ("New chat" / the
 *       first ask of a sitting); the id rides subsequent asks so their turns
 *       are persisted;</li>
 *   <li>{@code GET    /api/v1/tutor/sessions} — the learner's conversation
 *       list, most recently active first: derived title + turn count per chat,
 *       no transcript bodies (s143 — the ChatGPT-style history pane);</li>
 *   <li>{@code GET    /api/v1/tutor/sessions/{id}} — the §22 retrieval: the
 *       caller's own transcript, seq-ordered (refresh hydration, history);
 *       a session owned by another learner 404s exactly like an unknown id;</li>
 *   <li>{@code GET    /api/v1/tutor/sessions/latest} — convenience retrieval of
 *       the most recent session for refresh hydration (cross-device);</li>
 *   <li>{@code DELETE /api/v1/tutor/sessions/{id}} — delete one of the caller's
 *       own chats with its transcript (s143; §20 data minimization — the
 *       learner's own record, their call).</li>
 * </ul>
 *
 * <p>The {@code /stream} variant in the §22 sketch remains unimplemented:
 * no SSE backend exists (T-025's documented v0 posture), and the ask is
 * synchronous. Turn appends happen through {@code POST /api/v1/tutor/ask}
 * with a {@code sessionId} — sessions are written by the ask pipeline, never
 * hand-assembled by clients.</p>
 */
@RestController
@RequestMapping("/api/v1/tutor/sessions")
public class TutorSessionController {

    private final TutorSessionService sessions;

    public TutorSessionController(TutorSessionService sessions) {
        this.sessions = sessions;
    }

    public record CreatedSessionView(UUID sessionId, java.time.Instant createdAt) {
    }

    @PostMapping
    public ResponseEntity<CreatedSessionView> create(@CurrentUserId UUID learnerId) {
        TutorSessionService.SessionView created = sessions.create(learnerId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedSessionView(created.sessionId(), created.createdAt()));
    }

    /**
     * The caller's conversations, newest activity first (s143): summaries
     * only — the opening question as the derived title, a turn count, and
     * the recency stamps. The transcript itself is fetched per chat on
     * demand via {@code GET /{sessionId}}.
     */
    @GetMapping
    public List<TutorSessionService.SessionSummaryView> list(@CurrentUserId UUID learnerId) {
        return sessions.list(learnerId);
    }

    /** Order matters: /latest must not be captured by the {id} path variable. */
    @GetMapping("/latest")
    public ResponseEntity<TutorSessionService.SessionView> latest(@CurrentUserId UUID learnerId) {
        TutorSessionService.SessionView latest = sessions.latest(learnerId);
        return latest == null ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(latest);
    }

    @GetMapping("/{sessionId}")
    public TutorSessionService.SessionView view(@CurrentUserId UUID learnerId,
                                                @PathVariable UUID sessionId) {
        return sessions.view(learnerId, sessionId);
    }

    /**
     * Delete one of the caller's own chats (s143): 204 on success; a foreign
     * or unknown id 404s exactly like every other session operation — never
     * a signal about what exists.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@CurrentUserId UUID learnerId,
                                       @PathVariable UUID sessionId) {
        sessions.delete(learnerId, sessionId);
        return ResponseEntity.noContent().build();
    }
}
