package com.syllabai.assessment;

import com.syllabai.assessment.dto.AttemptResultView;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assessment use cases. On submission it persists the raw {@link Attempt} and then
 * <strong>emits evidence</strong> as {@link AssessmentEvidenceRecordedEvent} — the
 * learner model and research telemetry observe it; this service never updates mastery
 * itself (Master Spec §12 evidence contract, §23 Observer pattern).
 */
@Service
public class AssessmentService {

    private final QuestionRepository questions;
    private final QuestionTopicRepository questionTopics;
    private final AttemptRepository attempts;
    private final ApplicationEventPublisher events;

    public AssessmentService(QuestionRepository questions,
                             QuestionTopicRepository questionTopics,
                             AttemptRepository attempts,
                             ApplicationEventPublisher events) {
        this.questions = questions;
        this.questionTopics = questionTopics;
        this.attempts = attempts;
        this.events = events;
    }

    @Transactional
    public AttemptResultView submit(UUID learnerId, SubmitAnswerRequest request) {
        Question question = questions.findWithOptions(request.questionId())
                .filter(Question::active)
                .orElseThrow(() -> new NotFoundException("question", request.questionId()));

        QuestionOption chosen = question.options().stream()
                .filter(o -> o.id().equals(request.chosenOptionId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("option", request.chosenOptionId()));

        boolean correct = chosen.correct();
        int marksAwarded = correct ? question.marks() : 0;

        Attempt attempt = new Attempt(
                learnerId, question, chosen.id(), correct, marksAwarded,
                request.responseTimeMs(), request.confidence(),
                request.selfDoubtFlag(), request.timedCondition(),
                provenanceOf(request));
        attempt = attempts.save(attempt);

        List<UUID> topicNodeIds = topicNodeIds(question);
        List<UUID> expressedMisconceptionIds = chosen.misconceptionNodeId() == null
                ? List.of()
                : List.of(chosen.misconceptionNodeId());
        List<UUID> observedMisconceptionIds = observedMisconceptionIds(question);

        events.publishEvent(new AssessmentEvidenceRecordedEvent(
                attempt.id(), learnerId, question.id(), topicNodeIds,
                correct, question.marks(), marksAwarded,
                request.responseTimeMs(), request.confidence(),
                request.selfDoubtFlag(), request.timedCondition(),
                expressedMisconceptionIds, observedMisconceptionIds,
                attempt.provenance(), Instant.now()));

        String correctLabel = question.options().stream()
                .filter(QuestionOption::correct)
                .map(QuestionOption::label)
                .findFirst()
                .orElse(null);

        return new AttemptResultView(
                attempt.id(), question.id(), correct, marksAwarded, question.marks(),
                correctLabel, expressedMisconceptionIds, attempt.createdAt());
    }

    private List<UUID> topicNodeIds(Question question) {
        List<UUID> ids = new java.util.ArrayList<>();
        ids.add(question.primaryTopicNodeId());
        for (QuestionTopic qt : questionTopics.findByQuestionId(question.id())) {
            if (!qt.nodeId().equals(question.primaryTopicNodeId())) {
                ids.add(qt.nodeId());
            }
        }
        return ids;
    }

    /**
     * Every misconception node monitored by this question's distractors (Paper B §3.4).
     * These are the hypotheses the item can update: choosing a tagged distractor
     * expresses the misconception (strengthens), while a correct answer on this item
     * weakens all of them. Distinct and order-stable.
     */
    private List<UUID> observedMisconceptionIds(Question question) {
        return question.options().stream()
                .map(QuestionOption::misconceptionNodeId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private String provenanceOf(SubmitAnswerRequest request) {
        return request.timedCondition() ? "web-quiz-v0-timed" : "web-quiz-v0";
    }
}
