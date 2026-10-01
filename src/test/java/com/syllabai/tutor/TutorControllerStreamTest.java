package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * Stream endpoint orchestration (tutor SSE tranche): the §22 session append
 * rides the COMPLETED event (a failed stream persists nothing), a foreign
 * session id fails BEFORE the stream opens, and delivery-pool rejection
 * degrades into an honest error instead of a 500. Event serialization order
 * is pinned by KaRagServiceStreamTest; the wire format by the live probe.
 */
class TutorControllerStreamTest {

    private final KaRagService kaRag = mock(KaRagService.class);
    private final TutorSessionService sessionStore = mock(TutorSessionService.class);

    /** Queues tasks; the test pumps them after emitter initialization. */
    private static final class PumpingExecutor implements Executor {
        private final List<Runnable> tasks = new java.util.ArrayList<>();

        void pump() {
            List<Runnable> pending = new java.util.ArrayList<>(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }

        @Override public void execute(Runnable command) {
            tasks.add(command);
        }
    }

    private TutorController controller(PumpingExecutor executor) {
        return new TutorController(kaRag, sessionStore, executor);
    }

    private TutorController.TutorAskRequest request(UUID sessionId) {
        return new TutorController.TutorAskRequest("What is a mole?", List.of(), sessionId);
    }

    private TutorController.TutorAskRequest request(UUID sessionId, String courseRef) {
        return new TutorController.TutorAskRequest("What is a mole?", List.of(), sessionId,
                courseRef);
    }

    @Test
    @DisplayName("successful stream appends the §22 session exchange on completion only")
    void sessionAppendOnCompleted() {
        PumpingExecutor executor = new PumpingExecutor();
        TutorController controller = controller(executor);
        UUID sessionId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        List<TutorStreamEvent> events = List.of(
                new TutorStreamEvent.Citations(List.of(), true),
                new TutorStreamEvent.Meta("groq", "llama-3", false, 2),
                new TutorStreamEvent.Delta("answer text"),
                new TutorStreamEvent.Completed("answer text", "groq", "llama-3", false, 2,
                        123.4, List.of()));
        when(kaRag.askStream(eq(learnerId), any(), anyList(), eq(sessionId), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Flux.fromIterable(events));

        SseEmitter emitter = controller.askStream(learnerId, request(sessionId));

        // nothing persisted before the stream actually runs
        verify(sessionStore, never()).append(any(), any());
        executor.pump();

        ArgumentCaptor<TutorSessionService.AppendRequest> captor =
                ArgumentCaptor.forClass(TutorSessionService.AppendRequest.class);
        verify(sessionStore).append(eq(learnerId), captor.capture());
        TutorSessionService.AppendRequest append = captor.getValue();
        assertThat(append.sessionId()).isEqualTo(sessionId);
        assertThat(append.answer()).isEqualTo("answer text");
        assertThat(append.provider()).isEqualTo("groq");
        assertThat(append.model()).isEqualTo("llama-3");
        assertThat(append.refused()).isFalse();
        assertThat(append.evidenceCount()).isEqualTo(2);
        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("a mid-stream failure persists nothing (parity with a blocking ask that throws)")
    void failedStreamAppendsNothing() {
        PumpingExecutor executor = new PumpingExecutor();
        TutorController controller = controller(executor);
        UUID sessionId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        when(kaRag.askStream(eq(learnerId), any(), anyList(), eq(sessionId), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Flux.error(new TutorGenerationException(
                        GroundedTutorGenerator.UNAVAILABLE_MESSAGE)));

        controller.askStream(learnerId, request(sessionId));
        executor.pump();

        verify(sessionStore, never()).append(any(), any());
    }

    @Test
    @DisplayName("a foreign/unknown session id fails BEFORE the stream opens (404 JSON, not an SSE)")
    void foreignSessionFailsFast() {
        PumpingExecutor executor = new PumpingExecutor();
        TutorController controller = controller(executor);
        UUID learnerId = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalArgumentException("tutor session not found"))
                .when(sessionStore).requireOwned(learnerId, foreign);

        assertThatThrownBy(() -> controller.askStream(learnerId, request(foreign)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(kaRag, never()).askStream(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("delivery-pool saturation degrades to an honest error event, never a 500")
    void poolRejectionIsHonest() {
        Executor saturated = task -> {
            throw new RejectedExecutionException("pool saturated");
        };
        TutorController controller = new TutorController(kaRag, sessionStore, saturated);
        UUID learnerId = UUID.randomUUID();
        when(kaRag.askStream(any(), any(), any(), any(), any()))
                .thenReturn(Flux.empty());   // never reached

        SseEmitter emitter = controller.askStream(learnerId, request(null));

        assertThat(emitter).isNotNull();
        verify(kaRag, never()).askStream(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("V53: the courseRef rides the §22 probes, the pipeline call and the session append")
    void courseRefRidesPipelineAndAppend() {
        PumpingExecutor executor = new PumpingExecutor();
        TutorController controller = controller(executor);
        UUID sessionId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        when(kaRag.askStream(eq(learnerId), any(), anyList(), eq(sessionId), eq("4CH1-2017")))
                .thenReturn(Flux.fromIterable(List.of(
                        new TutorStreamEvent.Completed("answer", "groq", "llama-3", false, 1,
                                12.0, List.of()))));

        controller.askStream(learnerId, request(sessionId, "4CH1-2017"));
        executor.pump();

        // the consistency probe ran before the pipeline (same ordering as /ask)
        org.mockito.Mockito.verify(sessionStore)
                .requireCourseConsistent(learnerId, sessionId, "4CH1-2017");
        // the pipeline call carried the ref
        verify(kaRag).askStream(eq(learnerId), any(), anyList(), eq(sessionId), eq("4CH1-2017"));
        // and the persisted exchange records what the chat actually served
        ArgumentCaptor<TutorSessionService.AppendRequest> captor =
                ArgumentCaptor.forClass(TutorSessionService.AppendRequest.class);
        verify(sessionStore).append(eq(learnerId), captor.capture());
        assertThat(captor.getValue().courseRef()).isEqualTo("4CH1-2017");
    }

    @Test
    @DisplayName("V53 normalization: a blank courseRef is absent end to end — no probe, legacy pipeline call")
    void blankCourseRefNormalizesToAbsent() {
        PumpingExecutor executor = new PumpingExecutor();
        TutorController controller = controller(executor);
        UUID sessionId = UUID.randomUUID();
        UUID learnerId = UUID.randomUUID();
        when(kaRag.askStream(eq(learnerId), any(), anyList(), eq(sessionId), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Flux.fromIterable(List.of(
                        new TutorStreamEvent.Completed("answer", "groq", "llama-3", false, 1,
                                12.0, List.of()))));

        controller.askStream(learnerId, request(sessionId, "   "));
        executor.pump();

        // blank = absent: no course probe (nothing to be consistent with)
        verify(sessionStore, never()).requireCourseConsistent(any(), any(), any());
        // the pipeline and the append saw the ONE absent shape (null)
        verify(kaRag).askStream(eq(learnerId), any(), anyList(), eq(sessionId),
                org.mockito.ArgumentMatchers.isNull());
        // append fires on the Completed event — an empty flux emits none, so
        // the stub must carry one (same shape as courseRefRidesPipelineAndAppend)
        ArgumentCaptor<TutorSessionService.AppendRequest> captor =
                ArgumentCaptor.forClass(TutorSessionService.AppendRequest.class);
        verify(sessionStore).append(eq(learnerId), captor.capture());
        assertThat(captor.getValue().courseRef()).isNull();
    }
}
