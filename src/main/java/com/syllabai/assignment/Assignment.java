package com.syllabai.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One teacher assignment (V49 — the "assignments → core contract" tranche,
 * ADR-029 4.10): teacher-authored work targeting the cohort, built from the
 * hub's question bank and handed in by learners as append-only evidence
 * ({@link AssignmentSubmission}).
 *
 * <p>Design rules, mirroring the V47/V48 evidence-class discipline:</p>
 * <ul>
 *   <li>CONTENT REFS ARE OPAQUE, HUB-OWNED: {@code courseSlug} /
 *       {@code courseLabel} are the hub's course identity — core owns the
 *       workflow state, the hub owns content identity (the same ruling as
 *       V47's {@code card_id} / V48's {@code noteId}). Core has no course
 *       registry and deliberately does not pretend to.</li>
 *   <li>TARGETS ARE RESOLVED, NOT CLAIMED: every {@code specRefs} code was
 *       resolved against the ingested curriculum knowledge graph at creation
 *       time (fail-closed structural gate — UNIT/TOPIC/SUBTOPIC below the
 *       subject root; unknown codes, the subject root and the semantic layer
 *       are refused and nothing is written). The refs are stored as codes,
 *       not node ids, because they are the shared vocabulary the hub renders
 *       practice links from — the resolution happened at the boundary.</li>
 *   <li>The assignment itself is LIFECYCLE state (open/closed), not evidence:
 *       status flips are ordinary updates; the evidence trail lives in the
 *       append-only submissions table.</li>
 *   <li>NEVER mastery: creating or closing an assignment writes no
 *       SkillState / BKT / misconception / review state. Pinned by
 *       AssignmentFlowIT.</li>
 * </ul>
 */
@Entity
@Table(name = "assignments")
public class Assignment {

    public enum Status {
        OPEN, CLOSED;

        /** tolerant parse for the hub's wire forms ("open" / "closed") */
        public static Status parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase()) {
                case "open" -> OPEN;
                case "closed" -> CLOSED;
                default -> null;
            };
        }

        /** canonical wire form served to clients */
        public String wire() {
            return this == OPEN ? "open" : "closed";
        }
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    /** hub course slug (e.g. "igcse-chemistry-19") — opaque external reference */
    @Column(name = "course_slug", nullable = false, length = 64)
    private String courseSlug;

    /** hub course display label (e.g. "IGCSE Chemistry") — opaque external reference */
    @Column(name = "course_label", nullable = false, length = 120)
    private String courseLabel;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** validated curriculum-structure codes the assignment targets */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_refs", nullable = false, columnDefinition = "jsonb")
    private List<String> specRefs;

    @Column(name = "marks_total", nullable = false)
    private int marksTotal;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Assignment() {
        // JPA
    }

    public Assignment(UUID teacherId, String courseSlug, String courseLabel,
                      String title, List<String> specRefs, int marksTotal,
                      int questionCount, Instant dueAt, Status status) {
        this.teacherId = teacherId;
        this.courseSlug = courseSlug;
        this.courseLabel = courseLabel;
        this.title = title;
        this.specRefs = specRefs == null ? new ArrayList<>() : List.copyOf(specRefs);
        this.marksTotal = marksTotal;
        this.questionCount = questionCount;
        this.dueAt = dueAt;
        this.status = status.name();
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID teacherId() { return teacherId; }
    public String courseSlug() { return courseSlug; }
    public String courseLabel() { return courseLabel; }
    public String title() { return title; }
    public List<String> specRefs() { return specRefs; }
    public int marksTotal() { return marksTotal; }
    public int questionCount() { return questionCount; }
    public Instant dueAt() { return dueAt; }
    public Status status() { return Status.valueOf(status); }
    public void status(Status status) { this.status = status.name(); }
    public Instant createdAt() { return createdAt; }
}
