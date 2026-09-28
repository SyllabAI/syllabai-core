package com.syllabai.tutor;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.tutor.dto.TutorAnswerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Tutor ask endpoint (T-024 backend surface — the chat UI is T-025).
 * Authenticated: students ask, teachers preview with their own identity.
 *
 * <p>s139 working memory: the client sends the chat's prior turns with each
 * ask ({@code history}, newest-last). The history is the learner's own
 * visible transcript — held client-side, passed per-ask, and re-sanitized
 * here (the service layer is the policy boundary). Structural violations
 * (unknown role, oversized turns, too many turns) fail validation with a
 * 400; the service layer additionally sanitizes whatever passes, so a
 * well-formed-but-odd history degrades gracefully instead of failing the
 * ask.</p>
 *
 * <p>s140 §22 session persistence: an ask carrying a {@code sessionId}
 * created by {@code POST /api/v1/tutor/sessions} has its exchange (question
 * + answer) appended to that session after the pipeline completes. A foreign
 * or unknown session id fails the ask up front — the client only ever holds
 * ids it created, so this is an integrity signal, not a content check.
 * Omitted {@code sessionId} = unpersisted ask (pre-s140 clients).</p>
 *
 * <p>Streaming surface (tutor SSE tranche): {@code POST /ask/stream} speaks
 * the same request contract and runs the SAME pipeline, delivered as
 * {@code text/event-stream} — citations while the model thinks, meta with
 * the first token, sanitized incremental deltas, a lean {@code done}. The
 * blocking {@code /ask} remains byte-identical and is what the anchor-matrix
 * sweep pins; the stream endpoint is strictly additive. Session append for
 * a streamed exchange happens on successful completion (a failed stream
 * persists nothing, matching a blocking ask that throws). Errors before the
 * first byte (validation, foreign session, admission control) are normal
 * JSON error responses; errors after the stream opens travel as the wire
 * {@code error} event.</p>
 */
@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {

    private static final Logger log = LoggerFactory.getLogger(TutorController.class);

    private final KaRagService kaRag;
    private final TutorSessionService sessionStore;
    private final Executor streamExecutor;

    /** @Autowired is REQUIRED here: with two declared constructors Spring
     *  would fall back to a no-arg constructor (absent) and fail startup —
     *  the first Render deploy of this tranche died exactly that way. */
    @Autowired
    public TutorController(KaRagService kaRag, TutorSessionService sessionStore) {
        this(kaRag, sessionStore, defaultStreamExecutor());
    }

    /** test-visible constructor: stream delivery is driven synchronously or
     *  by a direct executor so the SSE sequence is deterministic in tests */
    TutorController(KaRagService kaRag, TutorSessionService sessionStore,
                    Executor streamExecutor) {
        this.kaRag = kaRag;
        this.sessionStore = sessionStore;
        this.streamExecutor = streamExecutor;
    }

    /**
     * Daemon bounded pool for stream delivery: SseEmitter writes block on
     * the servlet output, so they must not run on reactor event-loop threads
     * (the adapter already hands emissions to boundedElastic) nor pin the
     * request thread past the emitter handoff. Bounded queue + rejection
     * keeps a connection flood from allocating unbounded worker threads on
     * the Render CPU tier.
     */
    private static Executor defaultStreamExecutor() {
        return new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "tutor-stream");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * @param question  the turn to answer (unchanged contract)
     * @param history   optional prior turns of the same chat, oldest first;
     *                  null/absent = single-turn ask (pre-s139 clients)
     * @param sessionId optional §22 session to persist this exchange to;
     *                  null/absent = unpersisted ask (pre-s140 clients)
     */
    public record TutorAskRequest(
            @NotBlank @Size(max = 2000) String question,
            @Size(max = ConversationTurn.MAX_HISTORY_TURNS) List<@Valid HistoryTurn> history,
            UUID sessionId) {

        /** One client-held transcript turn. */
        public record HistoryTurn(
                @NotBlank @Pattern(regexp = ConversationTurn.ROLE_USER + "|"
                        + ConversationTurn.ROLE_ASSISTANT) String role,
                @NotBlank @Size(max = ConversationTurn.MAX_TURN_CHARS) String text) {
        }
    }

    @PostMapping("/ask")
    public TutorAnswerView ask(@CurrentUserId UUID learnerId,
                               @Valid @RequestBody TutorAskRequest request) {
        List<ConversationTurn> history = new ArrayList<>();
        if (request.history() != null) {
            for (TutorAskRequest.HistoryTurn turn : request.history()) {
                ConversationTurn clean = ConversationTurn.of(turn.role(), turn.text());
                if (clean != null) {
                    history.add(clean);
                }
            }
        }
        // §22 integrity probe BEFORE the pipeline: a foreign/unknown session id
        // fails fast (404) instead of spending an LLM call and failing after.
        if (request.sessionId() != null) {
            sessionStore.requireOwned(learnerId, request.sessionId());
        }
        TutorAnswerView answer = kaRag.ask(learnerId, request.question(), history,
                request.sessionId());
        if (request.sessionId() != null) {
            sessionStore.append(learnerId, new TutorSessionService.AppendRequest(
                    request.sessionId(), request.question(), answer.answer(),
                    answer.evidenceCount(), answer.refused(), answer.model(),
                    answer.provider(), answer.latencyMs()));
        }
        return answer;
    }

    /**
     * Streamed twin of {@link #ask}: same request contract, same validation,
     * same §22 integrity probe (failures here are ordinary JSON error
     * responses — the stream has not opened), same R8 LLM-tier budget (the
     * filter admits this route into the tier), then the pipeline delivered
     * as {@code text/event-stream}.
     *
     * <p>Delivery runs on the bounded daemon pool; the request thread returns
     * the emitter immediately after admission. The flux's idle timeout (and
     * the client's own disconnect) bound every stream — the servlet async
     * timeout is disabled ({@code 0L}) because the ONLY meaningful stall is
     * upstream, and the stream dies on the first silent gap anyway.</p>
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@CurrentUserId UUID learnerId,
                                @Valid @RequestBody TutorAskRequest request) {
        List<ConversationTurn> history = new ArrayList<>();
        if (request.history() != null) {
            for (TutorAskRequest.HistoryTurn turn : request.history()) {
                ConversationTurn clean = ConversationTurn.of(turn.role(), turn.text());
                if (clean != null) {
                    history.add(clean);
                }
            }
        }
        // same §22 integrity probe as /ask — BEFORE the stream opens, so a
        // foreign session id is a 404 JSON body, not an opened-then-failed stream
        if (request.sessionId() != null) {
            sessionStore.requireOwned(learnerId, request.sessionId());
        }

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable cleanup = () -> {
            Disposable d = subscription.get();
            if (d != null) {
                d.dispose();
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onError(t -> cleanup.run());
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });

        try {
            streamExecutor.execute(() -> runStream(learnerId, request, history, emitter, subscription));
        } catch (RejectedExecutionException e) {
            // admission control: honest, fixed text — the pool (and behind it,
            // the Render CPU tier) is saturated; never queue unbounded
            log.warn("tutor stream rejected: delivery pool saturated");
            sendWireError(emitter, "the tutor is busy right now — please try again shortly");
        }
        return emitter;
    }

    /** Subscribe the pipeline to the emitter; every event is serialized to
     *  the exact wire shape the hub proxy forwards and the browser parses. */
    private void runStream(UUID learnerId, TutorAskRequest request, List<ConversationTurn> history,
                           SseEmitter emitter, AtomicReference<Disposable> subscription) {
        try {
            Disposable d = kaRag.askStream(learnerId, request.question(), history, request.sessionId())
                    .subscribe(
                            event -> sendEvent(learnerId, request, emitter, event),
                            error -> {
                                // M2 contract: fixed client-safe text — provider error
                                // bodies and ops guidance stay in the server logs (the
                                // service already WARN-logged the diagnosable cause)
                                log.warn("tutor stream failed: {}", error.toString());
                                sendWireError(emitter,
                                        "the tutor is temporarily unavailable — please try again shortly");
                            },
                            emitter::complete);
            subscription.set(d);
        } catch (Exception e) {
            // pipeline construction failure (e.g. a retrieval-layer exception)
            log.warn("tutor stream failed before subscription: {}", e.toString());
            sendWireError(emitter,
                    "the tutor is temporarily unavailable — please try again shortly");
        }
    }

    private void sendEvent(UUID learnerId, TutorAskRequest request,
                           SseEmitter emitter, TutorStreamEvent event) {
        try {
            if (event instanceof TutorStreamEvent.Citations c) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("citations", c.citations());
                payload.put("sufficient", c.sufficient());
                emitter.send(SseEmitter.event().name("citations").data(payload));
            } else if (event instanceof TutorStreamEvent.Meta m) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("provider", m.provider());
                payload.put("model", m.model());          // null on refusals — Jackson writes it
                payload.put("refused", m.refused());
                payload.put("evidenceCount", m.evidenceCount());
                emitter.send(SseEmitter.event().name("meta").data(payload));
            } else if (event instanceof TutorStreamEvent.Delta d) {
                emitter.send(SseEmitter.event().name("delta")
                        .data(Map.of("text", d.text())));
            } else if (event instanceof TutorStreamEvent.Completed done) {
                // §22 session append on success only (parity with /ask where the
                // append follows a completed pipeline); the wire shape stays the
                // lean {ok:true} the browser already ignores-but-tolerates
                if (request.sessionId() != null) {
                    try {
                        sessionStore.append(learnerId, new TutorSessionService.AppendRequest(
                                request.sessionId(), request.question(), done.fullAnswer(),
                                done.evidenceCount(), done.refused(), done.model(),
                                done.provider(), done.latencyMs()));
                    } catch (RuntimeException e) {
                        // a persistence failure must not corrupt the delivered answer
                        log.error("tutor stream session append failed (session {}): {}",
                                request.sessionId(), e.toString());
                    }
                }
                emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true)));
            }
        } catch (IOException | IllegalStateException e) {
            // client went away (or the response is no longer writable): stop the
            // pipeline — the cancelled subscription releases the LLM stream
            throw new RuntimeException("tutor stream client disconnected", e);
        }
    }

    /** the wire error event + close; best-effort (the client may be gone). */
    private static void sendWireError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}
