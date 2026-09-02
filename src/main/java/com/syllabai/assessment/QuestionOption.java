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
 * An answer option. Wrong options are <em>distractors</em> and may be tagged with the
 * misconception they indicate (Paper B §3.4 BDT input, Master Spec §10).
 */
@Entity
@Table(name = "question_options")
public class QuestionOption {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "label", nullable = false, length = 4)
    private String label;                    // A, B, C, D

    @Column(name = "option_text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** misconception node implicated when a learner picks this distractor */
    @Column(name = "misconception_node_id")
    private UUID misconceptionNodeId;

    @Column(name = "ordering", nullable = false)
    private int ordering;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuestionOption() {
        // JPA
    }

    public QuestionOption(Question question, String label, String text, boolean correct,
                           UUID misconceptionNodeId, int ordering) {
        this.question = question;
        this.label = label;
        this.text = text;
        this.correct = correct;
        this.misconceptionNodeId = misconceptionNodeId;
        this.ordering = ordering;
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
    public Question question() { return question; }
    public UUID questionId() { return question.id(); }
    public String label() { return label; }
    public String text() { return text; }
    public boolean correct() { return correct; }
    public UUID misconceptionNodeId() { return misconceptionNodeId; }
    public int ordering() { return ordering; }
}
