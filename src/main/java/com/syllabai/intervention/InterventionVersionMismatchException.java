package com.syllabai.intervention;

import java.util.UUID;

public final class InterventionVersionMismatchException extends IllegalStateException {
    public InterventionVersionMismatchException(UUID runId, String expectedVersion, String expectedHash) {
        super("Intervention definition mismatch for run " + runId
                + "; expected version=" + expectedVersion + ", hash=" + expectedHash);
    }
}
