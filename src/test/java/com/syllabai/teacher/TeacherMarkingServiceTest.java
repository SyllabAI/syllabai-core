package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.EvidencePublisher;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.events.HumanMarkRecordedEvent;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.SmartMarkAgreementEvaluation;
import com.syllabai.smartmark.SmartMarkAgreementEvaluationRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Human-mark authority semantics (Master Spec §15): the first human mark fires the
 * evidence contract exactly once; a second mark on the same attempt is an override
 * that never re-fires evidence. κ evaluation pairs per-point decisions between the
 * latest accepted smart run and the latest human mark.
 */
class TeacherMarkingServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID MARKER = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();

    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final HumanMarkRepository humanMarks = mock(HumanMarkRepository.class);
    private final SmartMarkResultRepository smartMarkResults = mock(SmartMarkResultRepository.class);
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations =
            mock(SmartMarkAgreementEvaluationRepository.class);
    private final QuestionTopicRepository questionTopics = mock(QuestionTopicRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final EvidencePublisher evidencePublisher = new EvidencePublisher(published::add);

    private final TeacherMarkingService service = new TeacherMarkingService(
            answers, attempts, humanMarks, smartMarkResults, agreementEvaluations, questionTopics,
            evidencePublisher, published::add);

    private final Question question;
    private final QuestionVersion version;
    private final QuestionPart part;
    private final Attempt attempt;
    private final Answer answer;
    private final MarkPoint pointA;
    private final MarkPoint pointB;

    TeacherMarkingServiceTest() {
        question = new Question("q-1", Question.Type.STRUCTURED, "stem", 2, 3, 120,
                "Explain", TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        version = new QuestionVersion(question, 1, "stem", 2, 3, 120, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        part = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(part, UUID.randomUUID());
        version.addPart(part);

        attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, false, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answer = new Answer(attempt, part, "iron oxide and water");
        TestIds.withId(answer, UUID.randomUUID());

        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        pointA = new MarkPoint(scheme, part, "1-a", 0, "iron oxide", 1, List.of(), 0.9);
        TestIds.withId(pointA, UUID.randomUUID());
        pointB = new MarkPoint(scheme, part, "1-a", 1, "water", 1, List.of(), 0.9);
        TestIds.withId(pointB, UUID.randomUUID());
        scheme.addPoint(pointA);
        scheme.addPoint(pointB);

        when(answers.findAttemptIdById(any(UUID.class))).thenReturn(Optional.of(attempt.id()));
        when(attempts.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.of(attempt));
        when(answers.findWithPartAndAttempt(answer.id())).thenReturn(Optional.of(answer));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answer));
        when(answers.save(any(Answer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(humanMarks.save(any(HumanMark.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.findByQuestionId(question.id())).thenReturn(List.of());
        when(agreementEvaluations.save(any(SmartMarkAgreementEvaluation.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private Map<String, Integer> bothPoints() {
        Map<String, Integer> decisions = new LinkedHashMap<>();
        decisions.put(pointA.id().toString(), 1);
        decisions.put(pointB.id().toString(), 1);
        return decisions;
    }

    @Test
    @DisplayName("first human mark fires evidence exactly once and is authoritative")
    void firstHumanMarkFiresEvidence() {
        HumanMark mark = service.recordHumanMark(answer.id(), MARKER, 2, bothPoints(), "both");

        assertThat(mark.marksAwarded()).isEqualTo(2);
        assertThat(answer.markingState()).isEqualTo(Answer.MarkingState.HUMAN_MARKED);
        assertThat(attempt.markingState()).isEqualTo(Attempt.MarkingState.HUMAN_MARKED);
        assertThat(attempt.marksAwarded()).isEqualTo(2);
        assertThat(attempt.evidenceEmitted()).isTrue();
        assertThat(published.stream()
                .filter(e -> e instanceof com.syllabai.shared.events.AssessmentEvidenceRecordedEvent)
                .count()).isEqualTo(1);
        HumanMarkRecordedEvent event = (HumanMarkRecordedEvent) published.stream()
                .filter(e -> e instanceof HumanMarkRecordedEvent).findFirst().orElseThrow();
        assertThat(event.revising()).isFalse();
    }

    @Test
    @DisplayName("a second human mark on the same attempt is an override — evidence never re-fires")
    void overrideNeverRefiresEvidence() {
        service.recordHumanMark(answer.id(), MARKER, 2, bothPoints(), "first pass");

        service.recordHumanMark(answer.id(), MARKER, 1, bothPoints(), "on reflection: 1");

        assertThat(answer.markingState()).isEqualTo(Answer.MarkingState.OVERRIDDEN);
        assertThat(attempt.markingState()).isEqualTo(Attempt.MarkingState.OVERRIDDEN);
        assertThat(attempt.marksAwarded()).isEqualTo(1);
        // exactly one evidence event across both marks (guard held)
        long evidenceEvents = published.stream()
                .filter(e -> e instanceof com.syllabai.shared.events.AssessmentEvidenceRecordedEvent)
                .count();
        assertThat(evidenceEvents).isEqualTo(1);
        HumanMarkRecordedEvent second = (HumanMarkRecordedEvent) published.stream()
                .filter(e -> e instanceof HumanMarkRecordedEvent)
                .reduce((first, last) -> last).orElseThrow();
        assertThat(second.revising()).isTrue();
    }

    @Test
    @DisplayName("marks outside the part bound are rejected with 409 semantics")
    void outOfBoundsMarksRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.recordHumanMark(answer.id(), MARKER, 3, null, "too many"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("outside part bound");
    }

    @Test
    @DisplayName("multi-part attempt: evidence waits for the completing mark and carries the FULL total")
    void multiPartAttemptFiresEvidenceOnlyWhenMarkingCompletes() {
        // a second part on the same attempt — while it is PENDING the attempt's
        // total is partial, so part a's authoritative mark must NOT fire yet
        QuestionPart partB = new QuestionPart(version, "b", "part b", "State", 2, 1);
        TestIds.withId(partB, UUID.randomUUID());
        version.addPart(partB);
        Answer answerB = new Answer(attempt, partB, "a second written answer");
        TestIds.withId(answerB, UUID.randomUUID());
        when(answers.findWithPartAndAttempt(answerB.id())).thenReturn(Optional.of(answerB));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answer, answerB));

        // part a's mark: the attempt is INCOMPLETE — no evidence fires
        service.recordHumanMark(answer.id(), MARKER, 2, bothPoints(), "part a full");
        assertThat(attempt.evidenceEmitted()).isFalse();
        assertThat(published.stream()
                .filter(e -> e instanceof com.syllabai.shared.events.AssessmentEvidenceRecordedEvent)
                .count()).isZero();
        // the settled row still tracks the partial sum (research view)
        assertThat(attempt.marksAwarded()).isEqualTo(2);

        // the completing mark fires the evidence ONCE, with the FULL total —
        // the event's marks/correctness agree with the settled attempt row
        // (the production regression this test pins: the first-part mark used
        // to fire with the partial total)
        service.recordHumanMark(answerB.id(), MARKER, 2, bothPoints(), "part b full");
        assertThat(attempt.evidenceEmitted()).isTrue();
        assertThat(attempt.marksAwarded()).isEqualTo(4);
        var evidence = published.stream()
                .filter(e -> e instanceof com.syllabai.shared.events.AssessmentEvidenceRecordedEvent)
                .map(e -> (com.syllabai.shared.events.AssessmentEvidenceRecordedEvent) e)
                .toList();
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).marksAwarded()).isEqualTo(4);
        assertThat(evidence.get(0).correctness()).isTrue();
    }

    @Test
    @DisplayName("κ evaluation pairs smart/human point decisions and persists the gate result")
    void kappaEvaluationPairs() {
        // accepted smart run: pointA awarded, pointB not
        Map<String, Object> smartA = new LinkedHashMap<>();
        smartA.put("markPointId", pointA.id().toString());
        smartA.put("awarded", true);
        Map<String, Object> smartB = new LinkedHashMap<>();
        smartB.put("markPointId", pointB.id().toString());
        smartB.put("awarded", false);
        SmartMarkResult smart = new SmartMarkResult(answer, "test-model", 1, 0.9, true,
                List.of(smartA, smartB), null, "raw");
        TestIds.withId(smart, UUID.randomUUID());
        when(smartMarkResults.findLatest(answer.id())).thenReturn(Optional.of(smart));

        // human: both points awarded
        HumanMark human = new HumanMark(answer, MARKER, 2, bothPoints(), null);
        TestIds.withId(human, UUID.randomUUID());
        when(humanMarks.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(human));

        SmartMarkAgreementEvaluation evaluation =
                service.evaluateAgreement(null, MARKER);

        // pairs: (1,1) and (0,1) -> po = 0.5, pe = 0.5*1 + 0.5*0 = 0.5, κ = 0.0
        assertThat(evaluation.sampleSize()).isEqualTo(2);
        assertThat(evaluation.observedAgreement()).isEqualTo(0.5);
        assertThat(evaluation.kappa()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.scope()).isEqualTo(SmartMarkAgreementEvaluation.SCOPE_ALL);
    }

    @Test
    @DisplayName("κ evaluation fails loudly when nothing is paired")
    void kappaEmptyFails() {
        when(humanMarks.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.evaluateAgreement(null, MARKER))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no paired");
    }

    // ── κ pairing convention pin (G-4 agent calibration round, 2026-09-22) ──
    //
    // The release gate measures agreement under ONE binarization: the smart
    // side pairs by the breakdown's `awarded` boolean — ANY credit on a
    // (possibly compound) mark point pairs as 1 — and the human side clamps
    // any non-zero per-point decision to 1. The 2026-09-22 agent calibration
    // round measured the SAME frozen blind judgments as κ=0.31 under a strict
    // "fully earned" re-reading vs κ=1.00 under this production convention
    // (n=25): on compound multi-mark points a convention mismatch is the
    // difference between a spurious gate FAIL and a pass. These tests pin the
    // convention so the pairing cannot silently drift before or after the
    // operator's human reference round (runbook ADDENDUM: any credit = 1).

    @Test
    @DisplayName("κ convention pin: partial credit on a compound point pairs as smart=1 (any credit = 1)")
    void partialCreditOnCompoundPointPairsAsOne() {
        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        MarkPoint compound = new MarkPoint(scheme, part, "1-x", 2,
                "compound point bundling three sub-items", 3, List.of(), 0.9);
        TestIds.withId(compound, UUID.randomUUID());

        // smart: the compound point is awarded with PARTIAL credit (1 of 3 marks)
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("markPointId", compound.id().toString());
        entry.put("ref", "1-x");
        entry.put("marks", 3);
        entry.put("marksAwarded", 1);
        entry.put("awarded", true);
        SmartMarkResult smart = new SmartMarkResult(answer, "test-model", 1, 0.9, true,
                List.of(entry), null, "raw");
        TestIds.withId(smart, UUID.randomUUID());
        when(smartMarkResults.findLatest(answer.id())).thenReturn(Optional.of(smart));

        // human: the learner earned ANY credit on the point → 1 (pinned marker convention)
        HumanMark human = new HumanMark(answer, MARKER, 1,
                Map.of(compound.id().toString(), 1), "method mark earned");
        TestIds.withId(human, UUID.randomUUID());
        when(humanMarks.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(human));

        SmartMarkAgreementEvaluation evaluation = service.evaluateAgreement(null, MARKER);

        // the pair is (1,1): partial credit MUST pair as agreement — a pairing
        // that required the point FULLY earned (awarded && marksAwarded == marks)
        // would flip this to (0,1) and corrupt the gate row
        assertThat(evaluation.sampleSize()).isEqualTo(1);
        assertThat(evaluation.observedAgreement()).isEqualTo(1.0);
        assertThat(evaluation.kappa()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evaluation.passed()).isTrue();
    }

    @Test
    @DisplayName("κ convention pin: the same partial credit under a strict 'fully earned' human reading pairs as honest disagreement")
    void strictFullyEarnedHumanReadingPairsAsDisagreement() {
        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        MarkPoint compound = new MarkPoint(scheme, part, "1-x", 0,
                "compound point bundling three sub-items", 3, List.of(), 0.9);
        TestIds.withId(compound, UUID.randomUUID());

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("markPointId", compound.id().toString());
        entry.put("ref", "1-x");
        entry.put("marks", 3);
        entry.put("marksAwarded", 1);
        entry.put("awarded", true);
        SmartMarkResult smart = new SmartMarkResult(answer, "test-model", 1, 0.9, true,
                List.of(entry), null, "raw");
        TestIds.withId(smart, UUID.randomUUID());
        when(smartMarkResults.findLatest(answer.id())).thenReturn(Optional.of(smart));

        // human marks 0 because the point was not FULLY earned — the WRONG
        // convention for this gate (runbook ADDENDUM pins any credit = 1).
        // The pairing must record the disagreement honestly — it may never
        // normalize the human side toward the smart side or vice versa.
        HumanMark human = new HumanMark(answer, MARKER, 0,
                Map.of(compound.id().toString(), 0), "not fully earned");
        TestIds.withId(human, UUID.randomUUID());
        when(humanMarks.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(human));

        SmartMarkAgreementEvaluation evaluation = service.evaluateAgreement(null, MARKER);

        assertThat(evaluation.sampleSize()).isEqualTo(1);
        assertThat(evaluation.observedAgreement()).isZero();
        assertThat(evaluation.kappa()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(evaluation.passed()).isFalse();
    }
}
