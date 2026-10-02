package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.syllabai.learner.bkt.BktParams;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Format-aware emission resolution pins (S2/ADR-033) — the resolver is the single
 * place guess is priced, so its domain behaviour is pinned here exhaustively:
 * per-format priors, the C3 malformed-count guard, and the never-invert clamp.
 * Constants are hand-derived (1/N and the paper defaults), not computed by the
 * implementation under test.
 */
class LearnerPropertiesTest {

    private static final LearnerProperties.Bkt PAPER =
            new LearnerProperties.Bkt(0.1, 0.1, 0.25, 0.1, 0.05, 0.01);

    @Test
    @DisplayName("lenient defaults: zero/negative knobs fall back to the research-design values")
    void lenientDefaults() {
        LearnerProperties.Bkt blank = new LearnerProperties.Bkt(0, 0, 0, 0, 0, 0);
        assertThat(blank.l0()).isEqualTo(0.1);
        assertThat(blank.slip()).isEqualTo(0.1);
        assertThat(blank.guess()).isEqualTo(0.25);
        assertThat(blank.learnRate()).isEqualTo(0.1);
        assertThat(blank.shortAnswerGuess()).isEqualTo(0.05);
        assertThat(blank.structuredGuess()).isEqualTo(0.01);

        LearnerProperties defaults = new LearnerProperties(null, null, null, null);
        assertThat(defaults.bkt()).isEqualTo(blank);
    }

    @Test
    @DisplayName("MCQ: guess is 1/optionCount — the paper 0.25 is exactly N=4 (bit-identical legacy path)")
    void mcqOneOverN() {
        assertThat(PAPER.toParams("MCQ_SINGLE", 4).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("MCQ_SINGLE", 3).guess()).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(PAPER.toParams("MCQ_SINGLE", 5).guess()).isCloseTo(0.2, within(1e-12));
        assertThat(PAPER.toParams("MCQ_SINGLE", 2).guess()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("C3 guard: MCQ counts below 2 degrade to the paper default — never 1/0 or 1/1")
    void mcqMalformedCountDegrades() {
        // 1/1 = 1.0 would make wrong answers RAISE mastery (BktEngine wrong-path
        // divides by (1−guess)); 1/0 would throw inside BktParams and 500 the submit.
        assertThat(PAPER.toParams("MCQ_SINGLE", 1).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("MCQ_SINGLE", 0).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("MCQ_SINGLE", -3).guess()).isEqualTo(0.25);
        // the degraded params are still constructible and sane
        assertThat(PAPER.toParams("MCQ_SINGLE", 1).slip()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("SHORT_ANSWER and STRUCTURED use their lenient priors")
    void nonMcqPriors() {
        assertThat(PAPER.toParams("SHORT_ANSWER", 0).guess()).isEqualTo(0.05);
        assertThat(PAPER.toParams("STRUCTURED", 0).guess()).isEqualTo(0.01);
    }

    @Test
    @DisplayName("untyped/unknown formats keep the paper default — strict refinement, never a behaviour change")
    void untypedKeepsPaperDefault() {
        assertThat(PAPER.toParams(null, 0).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("", 0).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("FILL_IN", 3).guess()).isEqualTo(0.25);
        assertThat(PAPER.toParams("mcq_single", 4).guess()).isEqualTo(0.25); // case-sensitive names
    }

    @Test
    @DisplayName("never-invert clamp: a misconfigured guess degrades strictly below 1 − slip")
    void guessClampedBelowOneMinusSlip() {
        LearnerProperties.Bkt misconfigured =
                new LearnerProperties.Bkt(0.1, 0.1, 0.25, 0.1, 0.05, 0.95);
        BktParams resolved = misconfigured.toParams("STRUCTURED", 0);
        assertThat(resolved.guess()).isLessThan(1.0 - 0.1);
        assertThat(resolved.guess()).isCloseTo(0.9 - 1e-9, within(1e-15));

        // even the MCQ branch cannot escape the clamp
        BktParams mcq = misconfigured.toParams("MCQ_SINGLE", 2); // would be 0.5 — under the clamp
        assertThat(mcq.guess()).isCloseTo(0.5, within(1e-12));
        LearnerProperties.Bkt highDefault =
                new LearnerProperties.Bkt(0.1, 0.1, 0.97, 0.1, 0.05, 0.01);
        assertThat(highDefault.toParams(null, 0).guess()).isLessThan(0.9);
    }

    @Test
    @DisplayName("only guess is format-sensitive — slip, l0 and T stay global")
    void onlyGuessIsFormatAware() {
        BktParams legacy = PAPER.toParams();
        for (String type : new String[]{"MCQ_SINGLE", "SHORT_ANSWER", "STRUCTURED", null}) {
            BktParams p = PAPER.toParams(type, 4);
            assertThat(p.slip()).isEqualTo(legacy.slip());
            assertThat(p.l0()).isEqualTo(legacy.l0());
            assertThat(p.learnRate()).isEqualTo(legacy.learnRate());
        }
    }

    @Test
    @DisplayName("flashcard ladder (T-C53): the 4-arg compat shape carries the shipped default; malformed ladders degrade to it")
    void flashcardLadderDefaults() {
        // the pre-T-C53 4-arg shape (every existing call site) = the hub-parity ladder
        LearnerProperties defaults = new LearnerProperties(null, null, null, null);
        assertThat(defaults.flashcardReview().intervalDays())
                .containsExactly(1, 2, 4, 8, 16, 32);

        // explicit construction honors a well-formed custom ladder…
        LearnerProperties custom = new LearnerProperties(
                null, null, null, null, new LearnerProperties.FlashcardReview(List.of(3, 7)));
        assertThat(custom.flashcardReview().intervalDays()).containsExactly(3, 7);

        // …and lenient normalization matches the Bkt/Decay/Bdt convention
        assertThat(new LearnerProperties.FlashcardReview(null).intervalDays())
                .containsExactly(1, 2, 4, 8, 16, 32);
        assertThat(new LearnerProperties.FlashcardReview(List.of()).intervalDays())
                .containsExactly(1, 2, 4, 8, 16, 32);
        assertThat(new LearnerProperties.FlashcardReview(List.of(1, 0)).intervalDays())
                .containsExactly(1, 2, 4, 8, 16, 32);

        // the parameters round-trip into the domain type the scheduler consumes
        assertThat(custom.flashcardReview().toParams().intervalDaysFor(1)).isEqualTo(3);
        assertThat(custom.flashcardReview().toParams().intervalDaysFor(3)).isEqualTo(7);
    }
}
