package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.UUID;

public record MisconceptionStateView(
        UUID misconceptionNodeId, double probability, boolean active,
        int evidenceCount, Instant lastEvidenceAt) {
}
