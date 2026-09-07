package com.syllabai.assessment;

import com.syllabai.assessment.dto.AttemptHistoryView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the learner's attempt history (charter §14 Review Hub minimal slice).
 *
 * <p>Strictly a read model over the existing assessment evidence tables —
 * the architectural rule "no parallel tracking systems" holds: this service
 * writes nothing, derives no mastery, and invents no claims. It joins
 * {@code attempts} (already fetched with their question) to the learner's
 * {@code answers} for structured attempts, and resolves the topic node's
 * code/title via the shared knowledge graph.</p>
 */
@Service
public class AttemptHistoryService {

    /** Max items served per request — bounded surface, like the NBA portfolio cap. */
    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 50;
    private static final int STEM_EXCERPT_CHARS = 220;

    private final AttemptRepository attempts;
    private final AnswerRepository answers;
    private final KnowledgeGraphService graph;

    public AttemptHistoryService(AttemptRepository attempts,
                                 AnswerRepository answers,
                                 KnowledgeGraphService graph) {
        this.attempts = attempts;
        this.answers = answers;
        this.graph = graph;
    }

    @Transactional(readOnly = true)
    public AttemptHistoryView historyFor(UUID learnerId, Integer requestedLimit) {
        int limit = clampLimit(requestedLimit);
        Pageable page = PageRequest.of(0, limit);
        List<Attempt> rows = attempts.findByLearnerIdOrderByCreatedAtDesc(learnerId, page);
        long total = attempts.countByLearnerId(learnerId);

        List<AttemptHistoryView.Item> items = new ArrayList<>(rows.size());
        for (Attempt attempt : rows) {
            items.add(toItem(attempt));
        }
        return new AttemptHistoryView(learnerId, (int) total, items.size(), items);
    }

    private AttemptHistoryView.Item toItem(Attempt attempt) {
        Question question = attempt.question();
        boolean mcq = attempt.chosenOptionId() != null;

        String chosenLabel = null;
        String correctLabel = null;
        List<UUID> misconceptionIds = List.of();
        if (mcq) {
            // options are lazily fetched inside the read-only transaction —
            // the session-5 CI lesson (LazyInitializationException) does not apply here
            for (QuestionOption option : question.options()) {
                if (option.id().equals(attempt.chosenOptionId())) {
                    chosenLabel = option.label();
                    if (option.misconceptionNodeId() != null) {
                        misconceptionIds = List.of(option.misconceptionNodeId());
                    }
                }
                if (option.correct()) {
                    correctLabel = option.label();
                }
            }
        }

        List<AttemptHistoryView.PartItem> parts = List.of();
        Boolean correct = null;
        Integer marksAwarded = attempt.marksAwarded();
        if (!mcq) {
            List<AttemptHistoryView.PartItem> partItems = new ArrayList<>();
            for (Answer answer : answers.findByAttemptIdOrderByQuestionPartId(attempt.id())) {
                partItems.add(new AttemptHistoryView.PartItem(
                        answer.questionPartId(),
                        answer.questionPart().label(),
                        answer.questionPart().marks(),
                        answer.marksAwarded(),
                        answer.markingState().name()));
            }
            parts = partItems;
            // structured: attempt-level marks are the sum of authoritative part marks;
            // while any part is pending there is no honest attempt-level number
            if (partItems.stream().allMatch(p -> p.marksAwarded() != null)) {
                marksAwarded = partItems.stream()
                        .mapToInt(AttemptHistoryView.PartItem::marksAwarded)
                        .sum();
            } else {
                marksAwarded = null;
            }
        } else {
            correct = attempt.correct();
        }

        String topicCode = null;
        String topicTitle = null;
        KnowledgeNode topic = graph.node(question.primaryTopicNodeId());
        if (topic != null) {
            topicCode = topic.code();
            topicTitle = topic.title();
        }

        return new AttemptHistoryView.Item(
                attempt.id(),
                question.id(),
                question.type().name(),
                question.externalRef(),
                question.commandWord(),
                excerpt(question.stem()),
                question.marks(),
                question.primaryTopicNodeId(),
                topicCode,
                topicTitle,
                correct,
                marksAwarded,
                attempt.markingState().name(),
                attempt.evidenceEmitted(),
                chosenLabel,
                correctLabel,
                misconceptionIds,
                attempt.selfDoubtFlag(),
                attempt.timedCondition(),
                attempt.confidenceLevel(),
                attempt.responseTimeMs(),
                attempt.createdAt(),
                parts);
    }

    private static String excerpt(String stem) {
        Objects.requireNonNull(stem, "stem");
        String normalized = stem.strip().replaceAll("\\s+", " ");
        if (normalized.length() <= STEM_EXCERPT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, STEM_EXCERPT_CHARS - 1) + "…";
    }

    private static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
