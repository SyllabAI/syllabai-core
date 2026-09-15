package com.syllabai.intervention;

public enum InterventionRunStatus {
    CREATED,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
