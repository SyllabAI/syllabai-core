package com.syllabai.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pinned syllabus release, e.g. "Edexcel IAL Chemistry, specification issued 2018".
 * Knowledge-graph nodes belong to exactly one curriculum version (Master Spec §6.2),
 * so the pilot can reproduce results against a frozen spec.
 */
@Entity
@Table(name = "curriculum_versions")
public class CurriculumVersion {

    public enum Status { DRAFT, ACTIVE, ARCHIVED }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "board", nullable = false, length = 50)
    private String board;

    @Column(name = "qualification", nullable = false, length = 50)
    private String qualification;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CurriculumVersion() {
        // JPA
    }

    public CurriculumVersion(String board, String qualification, String code, String title, Status status) {
        this.board = board;
        this.qualification = qualification;
        this.code = code;
        this.title = title;
        this.status = status;
    }

    @PrePersist
    void onInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID id() { return id; }
    public String board() { return board; }
    public String qualification() { return qualification; }
    public String code() { return code; }
    public String title() { return title; }
    public Status status() { return status; }
    public Instant createdAt() { return createdAt; }
}
