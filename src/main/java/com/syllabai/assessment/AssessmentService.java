package com.syllabai.assessment;

import com.syllabai.assessment.dto.AttemptResultView;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredAttemptResultView;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assessment use cases. On submission it persists the raw {@link Attempt} and then
 * <strong>emits evidence</strong> as {@code AssessmentEvidenceRecordedEvent} — the
 * learner model and research telemetry observe it; this service never updates mastery
 * itself (Master Spec §12 evidence contract, §23 Observer pattern).
 *
 * <p>Two submission paths (V8):</p>
 * <ul>
 *   <li><b>MCQ</b> — auto-graded at submit; evidence fires immediately (unchanged
 *       contract from Wave 0/2).</li>
 *   <li><b>STRUCTURED</b> — multi-part written answers stored PENDING marking;
 *       evidence fires exactly once, at first authoritative marking (human mark, or
 *       Smart Mark once the κ ≥ 0.60 gate has released it).</li>
 * </ul>
 */
@Service
public class AssessmentService {

    private final QuestionRepository questions;
    private final QuestionTopicRepository questionTopics;
    private final QuestionVersionRepository questionVersions;
    private final ExamPaperRepository examPapers;
    private final AnswerRepository answers;
    private final AttemptRepository attempts;
    private final EvidencePublisher evidencePublisher;

    private final ServableQuestionSpec servable = new ServableQuestionSpec();

    public AssessmentService(QuestionRepository questions,
                             QuestionTopicRepository questionTopics,
                             QuestionVersionRepository questionVersions,
                             ExamPaperRepository examPapers,
                             AnswerRepository answers,
                             AttemptRepository attempts,
                             EvidencePublisher evidencePublisher) {
        this.questions = questions;
        this.questionTopics = questionTopics;
        this.questionVersions = questionVersions;
        this.examPapers = examPapers;
        this.answers = answers;
        this.attempts = attempts;
        this.evidencePublisher = evidencePublisher;
    }

    /**
     * V20 paper-level gate at the write path: an attempt against a question under
     * a REJECTED or FLAGGED paper is refused exactly like unvalidated content
     * (fail-closed 404, no state echo). Cheap by construction — the blocking-id
     * list is tiny (content-review states, not learner data).
     */
    private void assertPaperAllowsServing(Question question) {
        if (question.examPaperId() == null) {
            return; // paper-less (SEED_DEMO orphans): per-question rule only
        }
        if (examPapers.findIdsBlockingServing().contains(question.examPaperId())) {
            throw new NotFoundException("question", question.id());
        }
    }

    @Transactional
    public AttemptResultView submit(UUID learnerId, SubmitAnswerRequest request) {
        Question question = questions.findWithOptions(request.questionId())
                .filter(Question::active)
                .orElseThrow(() -> new NotFoundException("question", request.questionId()));

        // serving boundary at the write path too (§7: unvalidated content never
        // serves — not even as an attempt target): a STRUCTURED question is only
        // attemptable while its current version is VALIDATED. Fail-closed 404,
        // no state echo.
        if (question.type() == Question.Type.STRUCTURED) {
            QuestionVersion current = questionVersions
                    .findByQuestionIdOrderByVersionDesc(question.id()).stream()
                    .findFirst().orElse(null);
            if (!servable.isSatisfiedBy(question, current)) {
                throw new NotFoundException("question", request.questionId());
            }
        }
        assertPaperAllowsServing(question);

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

        List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
        List<UUID> expressedMisconceptionIds = chosen.misconceptionNodeId() == null
                ? List.of()
                : List.of(chosen.misconceptionNodeId());
        List<UUID> observedMisconceptionIds = observedMisconceptionIds(question);

        evidencePublisher.publishMcq(attempt, question, secondary,
                expressedMisconceptionIds, observedMisconceptionIds);

        String correctLabel = question.options().stream()
                .filter(QuestionOption::correct)
                .map(QuestionOption::label)
                .findFirst()
                .orElse(null);

        return new AttemptResultView(
                attempt.id(), question.id(), correct, marksAwarded, question.marks(),
                correctLabel, expressedMisconceptionIds, attempt.createdAt());
    }

    /**
     * Structured submission (Master Spec §6.5, §16): persists one answer per part of
     * the current question version, marks nothing, emits no evidence — the attempt
     * enters PENDING marking. Timed/untimed pairing rides on the attempt row.
     */
    @Transactional
    public StructuredAttemptResultView submitStructured(UUID learnerId,
                                                        StructuredSubmitRequest request) {
        Question question = questions.findById(request.questionId())
                .filter(Question::active)
                .filter(q -> q.type() == Question.Type.STRUCTURED)
                .orElseThrow(() -> new NotFoundException("structured question",
                        request.questionId()));

        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", question.id()));

        // serving boundary at the write path: only a VALIDATED current version is
        // attemptable (paper detail exposes ids to any authenticated user, so the
        // unvalidated gate must live HERE, not only on the read endpoints).
        // Fail-closed 404 — no part labels, no marks, no state echo.
        if (!servable.isSatisfiedBy(question, version)) {
            throw new NotFoundException("structured question", request.questionId());
        }
        assertPaperAllowsServing(question);

        List<QuestionPart> parts = version.parts();
        if (parts.isEmpty()) {
            throw new NotFoundException("parts for question version", version.id());
        }

        Map<UUID, PartAnswerRequest> byPartId = new HashMap<>();
        for (PartAnswerRequest pa : request.partAnswers()) {
            if (byPartId.put(pa.partId(), pa) != null) {
                throw new IllegalArgumentException(
                        "duplicate answer for part " + pa.partId());
            }
        }
        for (QuestionPart part : parts) {
            if (!byPartId.containsKey(part.id())) {
                throw new IllegalArgumentException(
                        "missing answer for part '" + part.label() + "'");
            }
        }

        Attempt attempt = new Attempt(
                learnerId, question, null, false, null,
                request.responseTimeMs(), request.confidence(),
                request.selfDoubtFlag(), request.timedCondition(),
                "web-structured-v1" + (request.timedCondition() ? "-timed" : ""));
        attempt.beginMarking();
        attempt = attempts.save(attempt);

        List<Answer> savedAnswers = new ArrayList<>();
        for (QuestionPart part : parts) {
            PartAnswerRequest pa = byPartId.get(part.id());
            savedAnswers.add(answers.save(new Answer(attempt, part,
                    pa.answerText() == null ? "" : pa.answerText().trim())));
        }

        return new StructuredAttemptResultView(
                attempt.id(), question.id(), question.marks(),
                attempt.markingState().name(), attempt.createdAt(),
                savedAnswers.stream()
                        .map(a -> new StructuredAttemptResultView.PartResult(
                                a.questionPartId(),
                                a.questionPart().label(),
                                a.questionPart().marks(),
                                a.markingState().name(),
                                a.marksAwarded()))
                        .toList());
    }

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
