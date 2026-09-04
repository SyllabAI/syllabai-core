package com.syllabai.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the KA-RAG pipeline (T-024) after every tutor interaction —
 * answered or refused. The research module observes this to append the
 * Paper B §3.5 chat-exchange record: the learning log must capture what the
 * tutor was asked, what evidence it used and which model answered, not just
 * that an answer exists.
 *
 * @param learnerId      asking learner (null = anonymous preview)
 * @param question       the question as asked (stripped)
 * @param matchedTopicIds deterministic intent-matched KG topics
 * @param evidenceCount  evidence items grounding the answer (0 on refusal)
 * @param evidenceSources source types of the evidence, citation order
 * @param refused        true when no evidence survived retrieval
 * @param answerModel    model identity (null on refusal)
 * @param promptVersion  registered prompt identity, e.g. "tutor-grounded/v1"
 * @param latencyMs      end-to-end pipeline latency
 * @param occurredAt     event time
 */
public record TutorAnsweredEvent(
        UUID learnerId,
        String question,
        List<UUID> matchedTopicIds,
        int evidenceCount,
        List<String> evidenceSources,
        boolean refused,
        String answerModel,
        String promptVersion,
        double latencyMs,
        Instant occurredAt) {
}
