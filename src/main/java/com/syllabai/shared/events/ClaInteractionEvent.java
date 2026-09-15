package com.syllabai.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the Contextual Learning Assistant (CLA) pipeline after every
 * contextual exchange — answered or refused (CLA contract §6.1). The CLA is
 * the first conversational surface to attach to Learner Interaction Memory
 * via the extension rules of
 * {@code docs/LEARNER_INTERACTION_MEMORY_IMPLEMENTATION.md} §4: it emits an
 * answered event carrying deterministic topic anchors (the SERVER-RESOLVED
 * ResourceContext — never model-invented) and the same provenance fields as
 * the free Tutor, plus the surface identity triple (response mode, context
 * kind, context reference).
 *
 * <p>Consumers: {@code TutorEngagementRecorder} classifies the ask
 * deterministically (the same precedence list as tutor asks — never with an
 * LLM) and appends provenance-bearing rows with
 * {@code surface = CONTEXTUAL_ASSISTANT}; the research module logs the full
 * exchange (question text, tool invocation trace) as audit/history
 * telemetry. Raw conversation never enters learner memory.</p>
 *
 * @param learnerId        asking learner (never null on the learner surface;
 *                         kept nullable for defensive symmetry with the tutor event)
 * @param question         the learner's question as asked (stripped)
 * @param matchedTopicIds  deterministic anchors — the resolved context topic(s)
 * @param evidenceCount    evidence items grounding the answer (0 on refusal)
 * @param evidenceSources  source types of the evidence, citation order
 * @param refused          true when no evidence survived retrieval
 * @param answerModel      model identity (null on deterministic refusal)
 * @param promptVersion    registered prompt identity actually used, e.g.
 *                         "tutor-grounded/v2" (reused verbatim from the Tutor stack)
 * @param latencyMs        end-to-end pipeline latency
 * @param occurredAt       event time
 * @param interventionType deterministic tutor-policy intervention type name
 *                         (null on deterministic refusal) — signal provenance
 * @param responseMode     EXPLAIN | SUMMARIZE (CLA contract §3)
 * @param contextKind      KG_TOPIC in step 1 (CLA contract §1 closed enum)
 * @param contextReference the resolved context anchor (topic node id)
 * @param tools            read-only tool invocation trace (contract §4.4):
 *                         audit lives in research telemetry ONLY — never in
 *                         learner memory
 */
public record ClaInteractionEvent(
        UUID learnerId,
        String question,
        List<UUID> matchedTopicIds,
        int evidenceCount,
        List<String> evidenceSources,
        boolean refused,
        String answerModel,
        String promptVersion,
        double latencyMs,
        Instant occurredAt,
        String interventionType,
        String responseMode,
        String contextKind,
        UUID contextReference,
        List<ToolInvocation> tools) {

    /** audit record of one read-only tool invocation (tool, args reference,
     * result size, latency). Arguments are ID REFERENCES, never learner text. */
    public record ToolInvocation(String tool, String args, int resultSize, long latencyMs) {
    }
}
