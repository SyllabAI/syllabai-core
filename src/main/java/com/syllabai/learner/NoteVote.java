package com.syllabai.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One note-vote event (V48 — the "note votes → core evidence class"
 * contract, ADR-029 tranche 4.9). Append-only: every Yes / No action from
 * the hub's "Was this revision note helpful?" footer appends a row; the
 * latest row per {@code (learner_id, note_id)} is the note's current vote
 * and a vote change is preserved as new evidence rather than overwriting
 * the old one.
 *
 * <p>Evidence class semantics — what this row deliberately is NOT:</p>
 * <ul>
 *   <li>NEVER mastery: no BKT update, no {@link SkillState} row. Voting on
 *       a note is self-report (the learner-model honesty rule); mastery
 *       comes from marked attempts only. Pinned by NoteVoteFlowIT, which
 *       asserts {@code SkillStateRepository} stays empty after votes.</li>
 *   <li>NEVER misconception evidence: the BDT engine reads assessment
 *       distractors, not content feedback.</li>
 *   <li>NEVER a content-quality judgement: the vote says what THIS learner
 *       found helpful, not what the note is worth — the content pipeline's
 *       VALIDATED states are a different, operator-owned promotion path.</li>
 * </ul>
 *
 * <p>Attribution: {@code nodeId} is the curriculum-structure knowledge node
 * the note is placed under (the hub's RULE_DERIVED note subtopic anchor,
 * e.g. 4CH1-S1-a — the same anchor family as deck placement), resolved
 * server-side against the ingested curriculum with the same structural gate
 * as V47 ratings — the controller refuses unknown codes, semantic-layer
 * nodes (CONCEPT/MISCONCEPTION) and the subject root, so a row can never
 * claim a node the curriculum does not have. {@code noteId} is the hub
 * content id ("rn_*") kept as an opaque external reference: the hub owns
 * note identity, core owns the learner model.</p>
 */
@Entity
@Table(name = "note_votes")
public class NoteVote {

    public enum Vote {
        HELPFUL, NOT_HELPFUL;

        /** tolerant parse for the hub's wire forms ("helpful" / "up") */
        public static Vote parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase()) {
                case "helpful", "up" -> HELPFUL;
                case "not-helpful", "nothelpful", "down" -> NOT_HELPFUL;
                default -> null;
            };
        }

        /** canonical wire form served to clients */
        public String wire() {
            return this == HELPFUL ? "helpful" : "not-helpful";
        }
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    /** the curriculum-structure node the note is placed under (resolved, fail-closed) */
    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    /** hub content id ("rn_*") — opaque external reference, hub-owned identity */
    @Column(name = "note_id", nullable = false, length = 64)
    private String noteId;

    @Column(name = "vote", nullable = false, length = 16)
    private String vote;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NoteVote() {
        // JPA
    }

    public NoteVote(UUID learnerId, UUID nodeId, String noteId, Vote vote,
                    Instant occurredAt) {
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.noteId = noteId;
        this.vote = vote.name();
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID nodeId() { return nodeId; }
    public String noteId() { return noteId; }
    public Vote vote() { return Vote.valueOf(vote); }
    public Instant occurredAt() { return occurredAt; }
    public Instant createdAt() { return createdAt; }
}
