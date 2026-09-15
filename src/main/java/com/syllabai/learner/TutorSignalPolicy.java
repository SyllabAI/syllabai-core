package com.syllabai.learner;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic MULTI-ROW tutor-signal policies (sprint-2 §9).
 *
 * <p>The V23/V25 row classifier records one signal TYPE per engagement row.
 * Some defensible signals need MORE than one row — repeated explanation
 * requests, unresolved questions, post-explanation engagement, repeated
 * engagement with the same topic. These are computed here, over the windowed
 * rows of ONE learner+topic, by fixed rules — never stored as row facts
 * (which would duplicate the substrate), never LLM-derived, never converted
 * into mastery. A tutor signal is evidence of engagement, not competence.</p>
 *
 * <p>Every function is pure over its input list; callers fetch the windowed
 * rows once and reuse them (no per-topic queries).</p>
 */
final class TutorSignalPolicy {

    /** the confusion family: signals that indicate the learner is struggling on the topic */
    static final Set<String> CONFUSION_SIGNALS = Set.of(
            "DOUBT_SIGNAL", "MISCONCEPTION_RELATED", "CLARIFICATION_REQUEST",
            "PREREQUISITE_HELP");

    /** how many explanation requests inside the window count as "repeated" */
    static final int REPEATED_EXPLANATION_THRESHOLD = 2;

    private TutorSignalPolicy() {
    }

    /** the learner asked for an explanation of this topic repeatedly inside the window */
    static boolean repeatedExplanationRequest(List<TutorTopicEngagement> rows) {
        return countBySignal(rows, "EXPLANATION_REQUEST") >= REPEATED_EXPLANATION_THRESHOLD;
    }

    /**
     * An ask on this topic got no grounded answer (the deterministic refusal
     * path) — the honest "unresolved question" fact, straight off the refused
     * flag (V23 semantics, unchanged).
     */
    static boolean unresolvedQuestion(List<TutorTopicEngagement> rows) {
        return rows.stream().anyMatch(TutorTopicEngagement::refused);
    }

    /**
     * Post-explanation engagement: an explanation request followed by a later
     * engagement row on the same topic — the learner came back after the
     * explanation. Strictly-later timestamp; equal timestamps do not count
     * (honesty over optimism).
     */
    static boolean postExplanationEngagement(List<TutorTopicEngagement> rows) {
        Instant firstExplanation = rows.stream()
                .filter(e -> "EXPLANATION_REQUEST".equals(e.signalType()))
                .map(TutorTopicEngagement::occurredAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (firstExplanation == null) {
            return false;
        }
        Instant first = firstExplanation;
        return rows.stream().anyMatch(e -> e.occurredAt().isAfter(first));
    }

    /** repeated engagement with the same topic inside the window (row count) */
    static int engagementCount(List<TutorTopicEngagement> rows) {
        return rows.size();
    }

    /** per-signal-type counts for one topic's windowed rows (display order: by type name) */
    static Map<String, Long> signalCounts(List<TutorTopicEngagement> rows) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (TutorTopicEngagement e : rows) {
            counts.merge(e.signalType() == null ? "TOPIC_ENGAGEMENT" : e.signalType(),
                    1L, Long::sum);
        }
        return counts;
    }

    /**
     * The most recent confusion-family signal timestamp per topic (§8 advance
     * pass 1 recency): topics the learner recently reported confusion about,
     * keyed by the latest such engagement.
     */
    static Map<UUID, Instant> lastConfusionAtByTopic(List<TutorTopicEngagement> rows) {
        Map<UUID, Instant> last = new java.util.HashMap<>();
        for (TutorTopicEngagement e : rows) {
            if (e.signalType() != null && CONFUSION_SIGNALS.contains(e.signalType())) {
                last.merge(e.nodeId(), e.occurredAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        return last;
    }

    private static long countBySignal(List<TutorTopicEngagement> rows, String signal) {
        return rows.stream().filter(e -> signal.equals(e.signalType())).count();
    }
}
