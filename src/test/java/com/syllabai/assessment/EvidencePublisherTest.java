package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.TestIds;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evidence-contract's once-only guard (Master Spec §12), pinned at the
 * publisher itself — the last line of defense shared by BOTH marking paths
 * (human marking and κ-released Smart Mark). Repeated processing of an
 * attempt whose evidence already fired can never emit a second event, and
 * the emitted event always describes the SETTLED attempt (full total,
 * conservative correctness rule), never an intermediate partial marking
 * state (the 2026-09-15 production evidence-cycle defect, fixed by fe01b87).
 */
class EvidencePublisherTest {

    private final List<Object> published = new ArrayList<>();
    private final EvidencePublisher publisher = new EvidencePublisher(published::add);

    private final Question question;
    private final Attempt attempt;

    EvidencePublisherTest() {
        question = new Question("q-1", Question.Type.STRUCTURED, "stem", 6, 3, 120,
                "Explain", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        attempt = new Attempt(UUID.randomUUID(), question, null, false, null,
                5000L, 4, false, false, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
    }

    @Test
    @DisplayName("publishGraded fires ONE event carrying the settled total and the conservative correctness rule")
    void gradedFiresOnceWithSettledTotal() {
        attempt.recordTotalMarks(6, 6);

        boolean fired = publisher.publishGraded(attempt, question, List.of());

        assertThat(fired).isTrue();
        assertThat(published).hasSize(1);
        AssessmentEvidenceRecordedEvent event = (AssessmentEvidenceRecordedEvent) published.get(0);
        assertThat(event.marksTotal()).isEqualTo(6);
        assertThat(event.marksAwarded()).isEqualTo(6);
        assertThat(event.correctness()).isTrue();   // full marks = mastery evidence
        assertThat(event.attemptId()).isEqualTo(attempt.id());
        assertThat(event.learnerId()).isEqualTo(attempt.learnerId());
        assertThat(event.questionId()).isEqualTo(question.id());
    }

    @Test
    @DisplayName("publishGraded is a no-op once evidence already fired — repeated processing cannot duplicate evidence")
    void gradedIsIdempotentOnceFired() {
        attempt.recordTotalMarks(6, 6);

        assertThat(publisher.publishGraded(attempt, question, List.of())).isTrue();
        assertThat(publisher.publishGraded(attempt, question, List.of())).isFalse();
        assertThat(publisher.publishGraded(attempt, question, List.of())).isFalse();

        assertThat(published).hasSize(1);   // still exactly one event
    }

    @Test
    @DisplayName("partial credit is honest: not full marks = not mastery evidence")
    void partialCreditIsNotMastery() {
        attempt.recordTotalMarks(4, 6);

        assertThat(publisher.publishGraded(attempt, question, List.of())).isTrue();

        AssessmentEvidenceRecordedEvent event = (AssessmentEvidenceRecordedEvent) published.get(0);
        assertThat(event.marksAwarded()).isEqualTo(4);
        assertThat(event.correctness()).isFalse();
    }

    @Test
    @DisplayName("publishMcq fails closed on a duplicate submit — exactly once or not at all")
    void mcqFailsClosedOnDuplicateSubmit() {
        publisher.publishMcq(attempt, question, List.of(), List.of(), List.of());
        assertThat(published).hasSize(1);

        assertThatThrownBy(() -> publisher.publishMcq(attempt, question, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly once");

        assertThat(published).hasSize(1);   // the duplicate submit emitted nothing
    }
}
