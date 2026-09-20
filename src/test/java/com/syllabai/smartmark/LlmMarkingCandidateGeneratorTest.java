package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.TestIds;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prompt v2 (V25, gap G-3): the scheme-level general-guidance section renders
 * only when the selected scheme carries guidance — per-point acceptance criteria
 * were never the right home for board-wide instructions ("accept ecf", "ignore
 * significant-figure penalties"). The registry version bump is the calibration
 * contract: prompt v2 runs are distinguishable from v1 rows in the run log.
 */
class LlmMarkingCandidateGeneratorTest {

    private final LlmMarkingCandidateGenerator generator = new LlmMarkingCandidateGenerator(null);

    @Test
    @DisplayName("prompt registry v2 is the V25 calibration contract")
    void registryVersionIsTwo() {
        assertThat(LlmMarkingCandidateGenerator.PROMPT_VERSION).isEqualTo("2");
        assertThat(LlmMarkingCandidateGenerator.PROMPT_REGISTRY_KEY)
                .isEqualTo("smart-mark-candidate");
    }

    @Test
    @DisplayName("a scheme with guidance gets its own SCHEME-LEVEL GENERAL INSTRUCTIONS section")
    void rendersGeneralGuidanceSection() {
        MarkingContext context = context("Accept ecf. Ignore significant figure penalties.");
        String prompt = generator.userPrompt(context);

        assertThat(prompt)
                .contains("SCHEME-LEVEL GENERAL INSTRUCTIONS")
                .contains("Accept ecf. Ignore significant figure penalties.");
        // section sits between the mark points and the learner answer
        assertThat(prompt.indexOf("SCHEME-LEVEL GENERAL INSTRUCTIONS"))
                .isGreaterThan(prompt.indexOf("MARK SCHEME POINTS"))
                .isLessThan(prompt.indexOf("LEARNER ANSWER"));
    }

    @Test
    @DisplayName("a scheme without guidance renders no section — v1-shaped prompt")
    void omitsSectionWhenAbsent() {
        assertThat(generator.userPrompt(context(null)))
                .doesNotContain("SCHEME-LEVEL GENERAL INSTRUCTIONS");
        assertThat(generator.userPrompt(context("   ")))
                .doesNotContain("SCHEME-LEVEL GENERAL INSTRUCTIONS");
    }

    private static MarkingContext context(String guidance) {
        Question question = new Question("q-1", Question.Type.STRUCTURED, "stem", 2, 3, 120,
                "Explain", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 2, 3, 120, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        QuestionPart part = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(part, UUID.randomUUID());
        version.addPart(part);

        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        scheme.setGeneralGuidance(guidance);
        MarkPoint point = new MarkPoint(scheme, part, "1-a", 0, "iron oxide", 1, List.of(), 0.9);
        TestIds.withId(point, UUID.randomUUID());
        scheme.addPoint(point);

        var attempt = new com.syllabai.assessment.Attempt(UUID.randomUUID(), question,
                null, false, null, 5000L, 4, false, true, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        Answer answer = new Answer(attempt, part, "an answer with water");
        TestIds.withId(answer, UUID.randomUUID());

        return new MarkingContext(answer, part, scheme, List.of(point));
    }
}
