package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A lettered/roman sub-question of a {@link QuestionVersion} — "(a) State…", "(b)(i)
 * Calculate…". Parts are the unit of learner answers ({@link Answer}) and of mark-point
 * attachment in mark schemes (Master Spec §6.5).
 */
@Entity
@Table(name = "question_parts")
public class QuestionPart {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_version_id", nullable = false)
    private QuestionVersion questionVersion;

    /** part label: "a", "b", "a-i", "ii", … */
    @Column(name = "label", nullable = false, length = 12)
    private String label;

    @Column(name = "prompt", nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "command_word", length = 30)
    private String commandWord;

    @Column(name = "marks", nullable = false)
    private int marks;

    @Column(name = "ordering", nullable = false)
    private int ordering;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuestionPart() {
        // JPA
    }

    public QuestionPart(QuestionVersion questionVersion, String label, String prompt,
                        String commandWord, int marks, int ordering) {
        this.questionVersion = questionVersion;
        this.label = label;
        this.prompt = prompt;
        this.commandWord = commandWord;
        this.marks = marks;
        this.ordering = ordering;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID questionVersionId() { return questionVersion.id(); }
    public QuestionVersion questionVersion() { return questionVersion; }
    public String label() { return label; }
    public String prompt() { return prompt; }
    public String commandWord() { return commandWord; }
    public int marks() { return marks; }
    public int ordering() { return ordering; }
    public Instant createdAt() { return createdAt; }

    public void updateContent(String prompt, String commandWord, int marks) {
        this.prompt = prompt;
        this.commandWord = commandWord;
        this.marks = marks;
    }
}
