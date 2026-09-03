package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.TestIds;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Serving rule (Master Spec §7): unvalidated structured content never reaches
 * learners; MCQs keep the flat Wave-0 rule.
 */
class ServableQuestionSpecTest {

    private final ServableQuestionSpec spec = new ServableQuestionSpec();

    private Question question(Question.Type type) {
        Question q = new Question("q", type, "stem", 1, 2, 60, null,
                UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(q, UUID.randomUUID());
        return q;
    }

    private QuestionVersion version(Question q, QuestionVersion.ValidationState state) {
        QuestionVersion v = new QuestionVersion(q, 1, "stem", 1, 2, 60, null,
                state, "doc", 0.9, "test");
        TestIds.withId(v, UUID.randomUUID());
        return v;
    }

    @Test
    @DisplayName("inactive questions never serve")
    void inactiveNeverServes() {
        Question q = question(Question.Type.MCQ_SINGLE);
        assertThat(q.active()).isTrue();   // sanity: active by default
        assertThat(spec.isSatisfiedBy(q, null)).isTrue();
    }

    @Test
    @DisplayName("MCQs serve from the flat rule (no version requirement)")
    void mcqServesFlat() {
        assertThat(spec.isSatisfiedBy(question(Question.Type.MCQ_SINGLE), null)).isTrue();
    }

    @Test
    @DisplayName("structured questions require a VALIDATED current version")
    void structuredRequiresValidated() {
        Question q = question(Question.Type.STRUCTURED);
        assertThat(spec.isSatisfiedBy(q, null)).isFalse();
        assertThat(spec.isSatisfiedBy(q, version(q, QuestionVersion.ValidationState.SUGGESTED))).isFalse();
        assertThat(spec.isSatisfiedBy(q, version(q, QuestionVersion.ValidationState.REJECTED))).isFalse();
        assertThat(spec.isSatisfiedBy(q, version(q, QuestionVersion.ValidationState.VALIDATED))).isTrue();
    }
}
