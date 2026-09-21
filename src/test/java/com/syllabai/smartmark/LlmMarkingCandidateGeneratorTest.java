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
 * Prompt v3 (V36, operator scenario 2026-09-21): per-point partial marks —
 * SME schemes bundle several examiner sub-points ("[1 mark]" annotations) into
 * one multi-mark row, and v2's boolean all-or-nothing allocation denied partial
 * credit on ~1,877 corpus parts. The registry version bump is the calibration
 * contract: prompt v3 runs are distinguishable from v1/v2 rows in the run log.
 */
class LlmMarkingCandidateGeneratorTest {

    private final LlmMarkingCandidateGenerator generator = new LlmMarkingCandidateGenerator(null);

    @Test
    @DisplayName("prompt registry v3 is the V36 calibration contract")
    void registryVersionIsThree() {
        assertThat(LlmMarkingCandidateGenerator.PROMPT_VERSION).isEqualTo("3");
        assertThat(LlmMarkingCandidateGenerator.PROMPT_REGISTRY_KEY)
                .isEqualTo("smart-mark-candidate");
        assertThat(generator.systemPrompt())
                .contains("marksAwarded")
                .contains("INDEPENDENTLY");
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

    @Test
    @DisplayName("v3 output: marksAwarded parses as partial credit, awarded derives")
    void parsesPartialMarks() {
        MarkingContext context = context(null, 3);
        MarkingCandidate candidate = generator.parse("""
                {"confidence": 0.9, "allocations": [{
                  "markPointId": "%s", "ref": "1-a", "marksAwarded": 1,
                  "evidence": "makes a squeaky pop sound",
                  "rationale": "squeaky pop earned; splint missed"}]}
                """.formatted(context.points().get(0).id()), context, "test-model");

        assertThat(candidate.allocations()).hasSize(1);
        var allocation = candidate.allocations().get(0);
        assertThat(allocation.marksAwarded()).isEqualTo(1);
        assertThat(allocation.awarded()).isTrue();   // any marks earned > 0
    }

    @Test
    @DisplayName("v3 output: an over-award clamps to the point's worth")
    void clampsOverAward() {
        MarkingContext context = context(null, 3);
        MarkingCandidate candidate = generator.parse("""
                {"confidence": 0.9, "allocations": [{
                  "markPointId": "%s", "ref": "1-a", "marksAwarded": 7,
                  "evidence": "x", "rationale": "arithmetic slip"}]}
                """.formatted(context.points().get(0).id()), context, "test-model");

        assertThat(candidate.allocations().get(0).marksAwarded()).isEqualTo(3);
    }

    @Test
    @DisplayName("v2-shaped output (boolean awarded only) still parses — whole-point semantics")
    void parsesLegacyBooleanShape() {
        MarkingContext context = context(null, 3);
        MarkingCandidate awarded = generator.parse("""
                {"confidence": 0.9, "allocations": [{
                  "markPointId": "%s", "ref": "1-a", "awarded": true,
                  "evidence": "x", "rationale": "full point"}]}
                """.formatted(context.points().get(0).id()), context, "test-model");
        assertThat(awarded.allocations().get(0).marksAwarded()).isEqualTo(3);

        MarkingCandidate denied = generator.parse("""
                {"confidence": 0.9, "allocations": [{
                  "markPointId": "%s", "ref": "1-a", "awarded": false,
                  "evidence": "", "rationale": "nothing"}]}
                """.formatted(context.points().get(0).id()), context, "test-model");
        assertThat(denied.allocations().get(0).marksAwarded()).isZero();
        assertThat(denied.allocations().get(0).awarded()).isFalse();
    }

    @Test
    @DisplayName("zero marksAwarded parses as not awarded (no credit, no crash)")
    void parsesZeroMarks() {
        MarkingContext context = context(null, 2);
        MarkingCandidate candidate = generator.parse("""
                {"confidence": 0.9, "allocations": [{
                  "markPointId": "%s", "ref": "1-a", "marksAwarded": 0,
                  "evidence": "", "rationale": "missing"}]}
                """.formatted(context.points().get(0).id()), context, "test-model");
        assertThat(candidate.allocations().get(0).marksAwarded()).isZero();
        assertThat(candidate.allocations().get(0).awarded()).isFalse();
    }

    private static MarkingContext context(String guidance) {
        return context(guidance, 1);
    }

    private static MarkingContext context(String guidance, int pointMarks) {
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
        MarkPoint point = new MarkPoint(scheme, part, "1-a", 0, "iron oxide", pointMarks, List.of(), 0.9);
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
