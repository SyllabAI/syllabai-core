package com.syllabai.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TutorInterventionSelectedEvent(
        UUID learnerId,
        List<UUID> topicNodeIds,
        String interventionType,
        String rationale,
        String policyVersion,
        Instant occurredAt) {
}
