package com.syllabai.tutor;

import com.syllabai.tutor.dto.TutorAnswerView;
import java.util.List;

/**
 * The tutor's streamed answer, event by event (tutor SSE tranche). The wire
 * contract (event name → payload) is what the hub proxy forwards verbatim
 * and the browser reader already consumes:
 *
 * <ol>
 *   <li>{@code citations} — resolved evidence citations, emitted BEFORE any
 *       generation runs (the learner sees sources while the model thinks);
 *       {@code sufficient} mirrors the evidence gate so the surface can
 *       render honest "thin evidence" signals;</li>
 *   <li>{@code meta} — provider/model identity + the refusal flag, committed
 *       with the FIRST generation delta (before that, no provider is
 *       committed); refusals carry their deterministic provider identity;</li>
 *   <li>{@code delta} — one sanitized increment of the answer text;</li>
 *   <li>{@code completed} — terminal success marker carrying the §22
 *       session-persistence summary (NOT serialized to the wire: the wire
 *       {@code done} event is the lean {@code \{ok:true\}} shape the browser
 *       already expects);</li>
 * </ol>
 *
 * <p>An error is NOT an event here — generation failures surface as the
 * Flux's error signal and the controller translates them into the wire
 * {@code error} event with the fixed client-safe message (deep-audit M2).</p>
 */
public sealed interface TutorStreamEvent {

    /** event: citations — evidence before generation. */
    record Citations(List<CitationResolver.Citation> citations, boolean sufficient)
            implements TutorStreamEvent {
    }

    /** event: meta — generation identity, committed with the first delta. */
    record Meta(String provider, String model, boolean refused, int evidenceCount)
            implements TutorStreamEvent {
    }

    /** event: delta — one sanitized increment. */
    record Delta(String text) implements TutorStreamEvent {
    }

    /** terminal success: the summary the controller needs for §22 persistence. */
    record Completed(String fullAnswer, String provider, String model, boolean refused,
                     int evidenceCount, double latencyMs,
                     List<TutorAnswerView.TopicMatch> topics) implements TutorStreamEvent {
    }
}
