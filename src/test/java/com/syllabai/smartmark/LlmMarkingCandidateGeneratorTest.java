package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.TestIds;
import com.syllabai.infrastructure.llm.FakeLlmProvider;
import com.syllabai.infrastructure.llm.LlmResponse;
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

    // ── completion budget + truncation observability (G-4 round 2026-09-22) ────
    //
    // The round's only UNPARSEABLE_OUTPUT (the reasoning-heaviest 5-mark compound
    // point) REPRODUCED on re-run: a reasoning model shares the completion budget
    // between reasoning and the visible JSON, and a flat 800 cap starves exactly
    // the heaviest judgments. The budget now scales with point marks, and a
    // budget-truncated completion refuses as TRUNCATED_OUTPUT with the raw text
    // attached instead of a generic parse failure with no forensic trace.

    @Test
    @DisplayName("completion budget scales with point marks; flat 800 floor kept as base")
    void completionBudgetScalesWithPointMarks() {
        assertThat(LlmMarkingCandidateGenerator.completionBudget(List.of())).isEqualTo(800);
        assertThat(LlmMarkingCandidateGenerator.completionBudget(
                context(null, 1).points())).isEqualTo(1_200);
        // the round's refused shape: one 5-mark compound point
        assertThat(LlmMarkingCandidateGenerator.completionBudget(
                context(null, 5).points())).isEqualTo(2_800);
        // pathological scheme cannot balloon the call
        assertThat(LlmMarkingCandidateGenerator.completionBudget(
                context(null, 9).points())).isEqualTo(4_000);
    }

    @Test
    @DisplayName("propose sends the scaled budget, not the starved flat 800")
    void proposeRequestsScaledBudget() {
        MarkingContext ctx = context(null, 5);
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith("""
                {"confidence": 0.9, "allocations": [{"markPointId": "%s", "ref": "1-a",
                 "marksAwarded": 2, "evidence": "x", "rationale": "r"}]}
                """.formatted(ctx.points().get(0).id()));
        LlmMarkingCandidateGenerator scaled = new LlmMarkingCandidateGenerator(chain);

        scaled.propose(ctx);

        assertThat(chain.lastRequest().maxTokens()).isEqualTo(2_800);
        assertThat(chain.lastRequest().temperature()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("a budget-truncated completion refuses as TRUNCATED_OUTPUT carrying the raw text")
    void truncatedCompletionRefusesWithRawOutput() {
        MarkingContext ctx = context(null, 5);
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith(new LlmResponse(
                "{\"confidence\": 0.9, \"allocations\": [{\"markPointId\"",
                "groq", "fake-model", 5L, 10, 10, "length"));
        LlmMarkingCandidateGenerator truncated = new LlmMarkingCandidateGenerator(chain);

        assertThatThrownBy(() -> truncated.propose(ctx))
                .isInstanceOf(CandidateGenerationException.class)
                .satisfies(e -> {
                    var refusal = (CandidateGenerationException) e;
                    assertThat(refusal.reason())
                            .isEqualTo(CandidateGenerationException.Reason.TRUNCATED_OUTPUT);
                    assertThat(refusal.rawOutput())
                            .startsWith("{\"confidence\": 0.9");
                });
    }

    @Test
    @DisplayName("max_tokens finish reason counts as truncation; stop finishes parse normally")
    void onlyTruncationFinishRefusesEarly() {
        assertThat(LlmMarkingCandidateGenerator.isTruncationFinish("length")).isTrue();
        assertThat(LlmMarkingCandidateGenerator.isTruncationFinish("MAX_TOKENS")).isTrue();
        assertThat(LlmMarkingCandidateGenerator.isTruncationFinish("stop")).isFalse();
        assertThat(LlmMarkingCandidateGenerator.isTruncationFinish(null)).isFalse();
        assertThat(LlmMarkingCandidateGenerator.isTruncationFinish("")).isFalse();

        // a genuinely finished completion parses even when the finish reason is present
        MarkingContext ctx = context(null, 1);
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith(new LlmResponse("""
                {"confidence": 0.9, "allocations": [{"markPointId": "%s", "ref": "1-a",
                 "marksAwarded": 1, "evidence": "x", "rationale": "r"}]}
                """.formatted(ctx.points().get(0).id()),
                "groq", "fake-model", 5L, 10, 10, "stop"));
        LlmMarkingCandidateGenerator stopped = new LlmMarkingCandidateGenerator(chain);

        assertThat(stopped.propose(ctx).allocations()).hasSize(1);
    }

    @Test
    @DisplayName("an unparseable (non-truncated) refusal carries the raw output too")
    void parseRefusalCarriesRawOutput() {
        MarkingContext ctx = context(null, 1);
        FakeLlmProvider chain = FakeLlmProvider.named("groq")
                .respondsWith("the model rambled without any JSON object at all");
        LlmMarkingCandidateGenerator rambling = new LlmMarkingCandidateGenerator(chain);

        assertThatThrownBy(() -> rambling.propose(ctx))
                .isInstanceOf(CandidateGenerationException.class)
                .satisfies(e -> {
                    var refusal = (CandidateGenerationException) e;
                    assertThat(refusal.reason())
                            .isEqualTo(CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT);
                    assertThat(refusal.rawOutput())
                            .isEqualTo("the model rambled without any JSON object at all");
                });
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
