package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bank question (Master Spec §10). Multi-topic questions are first-class:
 * the primary node lives here, secondary mappings in {@code question_topics}.
 * Distractors/options and their misconception tags live in {@link QuestionOption}.
 */
@Entity
@Table(name = "questions")
public class Question {

    public enum Type { MCQ_SINGLE, SHORT_ANSWER, STRUCTURED }   // MCQ end-to-end; STRUCTURED = multi-part (V8)
    public enum Provenance { PAST_PAPER, TEACHER_AUTHORED, SEED_DEMO }

    @Id
    @Column(name = "id")
    private UUID id;

    /** external reference, e.g. WCH11-2022-01-03a */
    @Column(name = "external_ref", length = 60)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    private Type type = Type.MCQ_SINGLE;

    @Column(name = "stem", nullable = false, columnDefinition = "text")
    private String stem;

    @Column(name = "marks", nullable = false)
    private int marks;

    /** 1–5 calibration-free difficulty label */
    @Column(name = "difficulty", nullable = false)
    private int difficulty;

    /** where the 1-5 difficulty came from (ADR-026/V29; null = legacy seed) */
    @Column(name = "difficulty_source", length = 30)
    private String difficultySource;

    @Column(name = "expected_time_seconds", nullable = false)
    private int expectedTimeSeconds;

    @Column(name = "command_word", length = 30)
    private String commandWord;

    /** primary KG node this question tests */
    @Column(name = "primary_topic_node_id", nullable = false)
    private UUID primaryTopicNodeId;

    /** exam paper this question belongs to (null = standalone bank item) */
    @Column(name = "exam_paper_id")
    private UUID examPaperId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 20)
    private Provenance provenance = Provenance.SEED_DEMO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "question")
    @OrderBy("ordering")
    private List<QuestionOption> options = new ArrayList<>();

    protected Question() {
        // JPA
    }

    public Question(String externalRef, Type type, String stem, int marks, int difficulty,
                    int expectedTimeSeconds, String commandWord, UUID primaryTopicNodeId,
                    Provenance provenance) {
        this.externalRef = externalRef;
        this.type = type;
        this.stem = stem;
        this.marks = marks;
        this.difficulty = difficulty;
        this.expectedTimeSeconds = expectedTimeSeconds;
        this.commandWord = commandWord;
        this.primaryTopicNodeId = primaryTopicNodeId;
        this.provenance = provenance;
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
    public String externalRef() { return externalRef; }
    public Type type() { return type; }
    public String stem() { return stem; }
    public int marks() { return marks; }
    public int difficulty() { return difficulty; }
    public String difficultySource() { return difficultySource; }
    public void setDifficultySource(String source) { this.difficultySource = source; }
    public int expectedTimeSeconds() { return expectedTimeSeconds; }
    public String commandWord() { return commandWord; }
    public UUID primaryTopicNodeId() { return primaryTopicNodeId; }
    public UUID examPaperId() { return examPaperId; }

    /** paper membership set once at ingestion (content never moves between papers) */
    public void attachToPaper(UUID paperId) {
        if (this.examPaperId != null && !this.examPaperId.equals(paperId)) {
            throw new IllegalStateException("question already belongs to another paper");
        }
        this.examPaperId = paperId;
    }

    /**
     * §10 topic re-mapping (teacher content review): move the question's primary
     * topic from its ingestion anchor to a real curriculum topic. Factual
     * association only — validation states and the serving boundary are untouched;
     * the mapping decides which subject's practice can see the question.
     */
    public void assignPrimaryTopic(UUID topicNodeId) {
        if (topicNodeId == null) {
            throw new IllegalArgumentException("primary topic must not be null");
        }
        this.primaryTopicNodeId = topicNodeId;
    }

    public Provenance provenance() { return provenance; }
    public boolean active() { return active; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }
    public List<QuestionOption> options() { return List.copyOf(options); }
}
