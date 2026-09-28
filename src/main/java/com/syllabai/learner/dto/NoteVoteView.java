package com.syllabai.learner.dto;

import com.syllabai.learner.NoteVote;
import java.time.Instant;
import java.util.UUID;

/**
 * One note-vote event as served to clients (V48, ADR-029 tranche 4.9).
 * {@code vote} uses the canonical wire vocabulary ("helpful" /
 * "not-helpful" — the hub's local "up"/"down" forms are accepted on POST
 * and mapped); {@code subtopicCode} echoes the resolved note anchor on the
 * POST response (null on the learner-state slice — clients attribute via
 * the node id or their own content bridge).
 */
public record NoteVoteView(
        String noteId, String vote, String subtopicCode, UUID nodeId, Instant occurredAt) {

    public static NoteVoteView from(NoteVote v, String subtopicCode) {
        return new NoteVoteView(v.noteId(), v.vote().wire(), subtopicCode, v.nodeId(),
                v.occurredAt());
    }
}
