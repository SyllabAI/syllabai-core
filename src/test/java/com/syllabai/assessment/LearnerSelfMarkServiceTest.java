package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Learner self-mark authority semantics (ADR-026 SME practice tranche): the
 * self-mark settles the caller's OWN structured attempt, fires the evidence
 * contract exactly once, and is recorded in learner_self_marks — never
 * HumanMark — so the κ agreement sample stays teacher-only by construction.
 */
class LearnerSelfMarkServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID OTHER_LEARNER = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();

    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final QuestionTopicRepository questionTopics = mock(QuestionTopicRepository.class);
    private final LearnerSelfMarkRepository selfMarks = mock(LearnerSelfMarkRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final EvidencePublisher evidencePublisher = new EvidencePublisher(published::add);

    private final LearnerSelfMarkService service = new LearnerSelfMarkService(
            attempts, answers, questionTopics, selfMarks, evidencePublisher);

    private final Question question;
    private final QuestionVersion version;
    private final QuestionPart partA;
    private final QuestionPart partB;
    private final Attempt attempt;
    private final Answer answerA;
    private final Answer answerB;

    LearnerSelfMarkServiceTest() {
        question = new Question("sme-eq-1-1-q3", Question.Type.STRUCTURED, "", 5, 3, 360,
                "Explain", TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        version = new QuestionVersion(question, 1, "", 5, 3, 360, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "sme-eq-igcse-chemistry-19",
                null, "sme-corpus-import-v1 (ADR-026)");
        TestIds.withId(version, UUID.randomUUID());
        partA = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(partA, UUID.randomUUID());
        partB = new QuestionPart(version, "b", "part b", "Explain", 3, 1);
        TestIds.withId(partB, UUID.randomUUID());
        version.addPart(partA);
        version.addPart(partB);

        attempt = new Attempt(LEARNER, question, null, false, null,
                30_000L, 4, false, false, "web-structured-v1");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answerA = new Answer(attempt, partA, "sodium chloride");
        TestIds.withId(answerA, UUID.randomUUID());
        answerB = new Answer(attempt, partB, "because of the lattice");
        TestIds.withId(answerB, UUID.randomUUID());

        when(attempts.findByIdForUpdate(attempt.id())).thenReturn(Optional.of(attempt));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answerA, answerB));
        when(questionTopics.findByQuestionId(question.id())).thenReturn(List.of());
    }

    @Test
    @DisplayName("self-mark settles every part, records provenance rows and fires evidence once")
    void selfMarkSettlesAndFiresEvidence() {
        var result = service.selfMark(LEARNER, attempt.id(),
                Map.of(partA.id(), 2, partB.id(), 1), null);

        assertThat(answerA.markingState()).isEqualTo(Answer.MarkingState.SELF_MARKED);
        assertThat(answerA.marksAwarded()).isEqualTo(2);
        assertThat(answerB.markingState()).isEqualTo(Answer.MarkingState.SELF_MARKED);
        assertThat(answerB.marksAwarded()).isEqualTo(1);
        assertThat(attempt.markingState()).isEqualTo(Attempt.MarkingState.SELF_MARKED);
        assertThat(attempt.marksAwarded()).isEqualTo(3);
        assertThat(attempt.correct()).isFalse(); // conservative rule: full marks only
        assertThat(result.evidenceFired()).isTrue();
        assertThat(published).hasSize(1);

        ArgumentCaptor<LearnerSelfMark> saved = ArgumentCaptor.forClass(LearnerSelfMark.class);
        verify(selfMarks, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).hasSize(2);
        assertThat(saved.getAllValues())
                .allSatisfy(m -> {
                    assertThat(m.learnerId()).isEqualTo(LEARNER);
                    assertThat(m.answer().attemptId()).isEqualTo(attempt.id());
                });
    }

    @Test
    @DisplayName("another learner's attempt id is a plain 404 — no existence leak")
    void foreignAttemptIsNotFound() {
        assertThatThrownBy(() -> service.selfMark(OTHER_LEARNER, attempt.id(),
                Map.of(partA.id(), 1, partB.id(), 1), null))
                .isInstanceOf(NotFoundException.class);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("marks outside a part's bound are rejected fail-closed")
    void boundViolationRejected() {
        assertThatThrownBy(() -> service.selfMark(LEARNER, attempt.id(),
                Map.of(partA.id(), 3, partB.id(), 1), null))
                .isInstanceOf(ConflictException.class);
        assertThat(answerA.markingState()).isEqualTo(Answer.MarkingState.PENDING);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a teacher-settled attempt cannot be self-marked — single-shot by design")
    void alreadySettledRejected() {
        answerA.humanMarked(2);
        answerB.humanMarked(3);
        assertThatThrownBy(() -> service.selfMark(LEARNER, attempt.id(),
                Map.of(partA.id(), 2, partB.id(), 3), null))
                .isInstanceOf(ConflictException.class);
        assertThat(answerA.markingState()).isEqualTo(Answer.MarkingState.HUMAN_MARKED);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a SMART_MARKED answer may still be self-marked (provisional → authoritative)")
    void smartMarkedAnswerCanBeSelfMarked() {
        answerA.smartMarked(2);
        var result = service.selfMark(LEARNER, attempt.id(),
                Map.of(partA.id(), 2, partB.id(), 3), "checked against the scheme");
        assertThat(answerA.markingState()).isEqualTo(Answer.MarkingState.SELF_MARKED);
        assertThat(result.evidenceFired()).isTrue();
    }

    @Test
    @DisplayName("the part set must match the attempt exactly — no extras, no omissions")
    void partSetMismatchRejected() {
        Map<UUID, Integer> partial = new LinkedHashMap<>();
        partial.put(partA.id(), 1); // missing partB
        assertThatThrownBy(() -> service.selfMark(LEARNER, attempt.id(), partial, null))
                .isInstanceOf(BadRequestException.class);

        Map<UUID, Integer> extra = new LinkedHashMap<>();
        extra.put(partA.id(), 1);
        extra.put(partB.id(), 1);
        extra.put(UUID.randomUUID(), 1); // unknown part
        assertThatThrownBy(() -> service.selfMark(LEARNER, attempt.id(), extra, null))
                .isInstanceOf(BadRequestException.class);
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("MCQ attempts are auto-graded — self-marking applies to structured only")
    void mcqAttemptRejected() {
        Question mcq = new Question("sme-eq-1-2-q1-p1", Question.Type.MCQ_SINGLE, "stem", 1, 2, 90,
                null, TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(mcq, UUID.randomUUID());
        Attempt mcqAttempt = new Attempt(LEARNER, mcq, UUID.randomUUID(), true, null,
                10_000L, 4, false, false, "test");
        TestIds.withId(mcqAttempt, UUID.randomUUID());
        when(attempts.findByIdForUpdate(mcqAttempt.id())).thenReturn(Optional.of(mcqAttempt));

        assertThatThrownBy(() -> service.selfMark(LEARNER, mcqAttempt.id(),
                Map.of(UUID.randomUUID(), 1), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("empty self-mark payload rejected before any state change")
    void emptyPayloadRejected() {
        assertThatThrownBy(() -> service.selfMark(LEARNER, attempt.id(), Map.of(), null))
                .isInstanceOf(BadRequestException.class);
        assertThat(answerA.markingState()).isEqualTo(Answer.MarkingState.PENDING);
    }
}
