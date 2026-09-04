package com.syllabai.shared.events;

import com.syllabai.diagnostic.StruggleType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StruggleInferredEvent(
        UUID learnerId,
        UUID topicNodeId,
        StruggleType type,
        String subtype,
        double probability,
        Map<String, Object> supportingEvidence,
        String modelVersion,
        Instant occurredAt) {
}
