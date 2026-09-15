package com.syllabai.intervention;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterventionRunServiceTest {

    @Mock InterventionRunRepository runs;
    @Mock InterventionRunStepRepository steps;
    @Mock InterventionRunEvidenceRepository evidence;

    @Test
    void createsWithStableInterventionHashWhenHashIsOmitted() {
        InterventionRunService service = new InterventionRunService(runs, steps, evidence);
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InterventionRunService.CreateCommand command = command(null);
        InterventionRun run = service.create(command);

        assertEquals(InterventionRunStatus.CREATED, run.status());
        assertNotNull(run.interventionHash());
        assertEquals(64, run.interventionHash().length());
        verify(runs).save(run);
    }

    @Test
    void resumeFailsClosedWhenInterventionDefinitionChanges() {
        InterventionRunService service = new InterventionRunService(runs, steps, evidence);
        InterventionRun run = new InterventionRun(
                UUID.randomUUID(), null, null, "NBA", "[\"sp:1\"]", "[]", "[\"ev:1\"]",
                "diag:1", "state:1", "diag-v1", "PRACTICE", "practice-v1", "hash-v1", "[\"start_practice\"]");
        when(runs.findById(run.runId())).thenReturn(Optional.of(run));

        assertThrows(InterventionVersionMismatchException.class,
                () -> service.resume(run.runId(), "practice-v2", "hash-v2"));
        assertEquals(InterventionRunStatus.CREATED, run.status());
    }

    @Test
    void resumeSucceedsWhenInterventionDefinitionMatches() {
        InterventionRunService service = new InterventionRunService(runs, steps, evidence);
        InterventionRun run = new InterventionRun(
                UUID.randomUUID(), null, null, "NBA", "[\"sp:1\"]", "[]", "[\"ev:1\"]",
                "diag:1", "state:1", "diag-v1", "PRACTICE", "practice-v1", "hash-v1", "[\"start_practice\"]");
        run.activate(Instant.now());
        run.pause();
        when(runs.findById(run.runId())).thenReturn(Optional.of(run));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InterventionRun resumed = service.resume(run.runId(), "practice-v1", "hash-v1");

        assertSame(run, resumed);
        assertEquals(InterventionRunStatus.ACTIVE, resumed.status());
        verify(runs).save(run);
    }

    @Test
    void terminalRunCannotBeChanged() {
        InterventionRun run = new InterventionRun(
                UUID.randomUUID(), null, null, "NBA", "[]", "[]", "[]",
                null, null, null, "PRACTICE", "v1", "hash", "[]");
        run.activate(Instant.now());
        run.complete("EVIDENCE_COLLECTED", Instant.now());

        assertThrows(IllegalStateException.class, () -> run.activate(Instant.now()));
        assertThrows(IllegalStateException.class, () -> run.cancel(Instant.now()));
    }

    private static InterventionRunService.CreateCommand command(String hash) {
        return new InterventionRunService.CreateCommand(
                UUID.randomUUID(), null, null, "NBA", "[\"sp:1\"]", "[]", "[\"ev:1\"]",
                "diag:1", "state:1", "diag-v1", "PRACTICE", "practice-v1", hash,
                "[\"get_specification_context\",\"start_practice\"]");
    }
}
