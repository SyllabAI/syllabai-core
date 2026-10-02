package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Blocking {@code /ask} orchestration: the §22 session append rides AFTER a
 * completed pipeline, and — parity with the stream path's COMPLETED guard
 * (audit L1, 2026-10-02) — a session persistence failure must not 5xx an
 * answer the learner already paid the full pipeline for. The exchange is
 * lost from the transcript; the answer itself is delivered.
 */
class TutorControllerAskTest {

    private final KaRagService kaRag = mock(KaRagService.class);
    private final TutorSessionService sessionStore = mock(TutorSessionService.class);

    private TutorController controller() {
        return new TutorController(kaRag, sessionStore, Runnable::run);
    }

    @Test
    @DisplayName("a failed session append does not fail the ask — the answer is delivered (audit L1)")
    void appendFailureDoesNotFailTheAsk() {
        UUID learnerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        TutorAnswerView answer = TutorAnswerView.of("the mole", List.of(), List.of(),
                2, "llama-3", "groq", false, 100.0);
        when(kaRag.ask(eq(learnerId), any(), anyList(), eq(sessionId),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(answer);
        doThrow(new RuntimeException("neon jitter: connection reset"))
                .when(sessionStore).append(eq(learnerId), any());

        TutorAnswerView delivered = controller().ask(learnerId,
                new TutorController.TutorAskRequest("What is a mole?", List.of(), sessionId));

        assertThat(delivered).isSameAs(answer);
        verify(sessionStore).append(eq(learnerId), any());
    }

    @Test
    @DisplayName("successful ask still appends the §22 exchange — the guard is fail-open only")
    void appendStillHappensOnSuccess() {
        UUID learnerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        TutorAnswerView answer = TutorAnswerView.of("the mole", List.of(), List.of(),
                2, "llama-3", "groq", false, 100.0);
        when(kaRag.ask(eq(learnerId), any(), anyList(), eq(sessionId),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(answer);

        controller().ask(learnerId,
                new TutorController.TutorAskRequest("What is a mole?", List.of(), sessionId));

        verify(sessionStore).append(eq(learnerId), any());
    }
}
