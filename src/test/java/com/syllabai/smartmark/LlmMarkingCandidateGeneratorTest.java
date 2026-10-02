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
import com.syllabai.infrastructure.llm.LlmReasoningEffort;
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
        // marking = rubric-shaped extraction: the request asks for bounded thinking
        assertThat(chain.lastRequest().reasoningEffort()).isEqualTo(LlmReasoningEffort.LOW);
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

    // ── attempt-batch topology (prompt v4, single-call marking) ────────────────
    //
    // The learner pass marks every answered part of an attempt; v4 transports all
    // parts in ONE provider call ({"parts": [...]} keyed by 1-based part number)
    // instead of K serial reasoning round trips. Marking rules are byte-identical
    // to v3; parse refusals are whole-batch so the pipeline falls back per part.

    @Test
    @DisplayName("batch envelope is registry v4; marking rules carry over byte-for-byte")
    void batchRegistryVersionIsFour() {
        assertThat(LlmMarkingCandidateGenerator.BATCH_PROMPT_VERSION).isEqualTo("4");
        assertThat(generator.batchSystemPrompt())
                .contains("INDEPENDENTLY")
                .contains("marksAwarded")
                .contains("\"parts\"")
                .contains("EVERY listed part")
                // the v3 single-part envelope must NOT leak into the batch prompt
                .doesNotContain("{\"confidence\": <0..1>, \"allocations\":");
    }

    @Test
    @DisplayName("batch completion budget scales with the whole attempt's marks, batch cap 8000")
    void batchCompletionBudgetScalesAcrossContexts() {
        // two parts × one 2-mark point = 4 marks → 800 + 1600
        assertThat(LlmMarkingCandidateGenerator.batchCompletionBudget(
                batchContexts(2, 2))).isEqualTo(2_400);
        // two parts × one 5-mark point = 10 marks → 800 + 4000
        assertThat(LlmMarkingCandidateGenerator.batchCompletionBudget(
                batchContexts(5, 5))).isEqualTo(4_800);
        // pathological attempt cannot balloon the single call
        assertThat(LlmMarkingCandidateGenerator.batchCompletionBudget(
                batchContexts(9, 9))).isEqualTo(8_000);
    }

    @Test
    @DisplayName("proposeAll marks every part in ONE provider call and parses per part")
    void proposeAllSendsOneBatchedCall() {
        List<MarkingContext> contexts = batchContexts(2, 3);
        MarkingContext partOne = contexts.get(0);
        MarkingContext partTwo = contexts.get(1);
        String batchJson = """
                {"parts": [
                  {"part": 1, "confidence": 0.8, "allocations": [{"markPointId": "%s",
                   "ref": "a-1", "marksAwarded": 2, "evidence": "part a quote",
                   "rationale": "both sub-points earned"}]},
                  {"part": 2, "confidence": 0.6, "allocations": [{"markPointId": "%s",
                   "ref": "b-1", "marksAwarded": 1, "evidence": "part b quote",
                   "rationale": "one of three sub-points earned"}]}
                ]}
                """.formatted(partOne.points().get(0).id(), partTwo.points().get(0).id());
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith(batchJson);
        LlmMarkingCandidateGenerator batched = new LlmMarkingCandidateGenerator(chain);

        List<MarkingCandidate> candidates = batched.proposeAll(contexts);

        // THE pin: one provider round trip for a two-part attempt, batch budget
        assertThat(chain.callCount()).isEqualTo(1);
        assertThat(chain.lastRequest().maxTokens()).isEqualTo(2_800);
        assertThat(chain.lastRequest().temperature()).isEqualTo(0.1);
        assertThat(chain.lastRequest().systemPrompt()).contains("\"parts\"");
        assertThat(chain.lastRequest().reasoningEffort()).isEqualTo(LlmReasoningEffort.LOW);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).allocations()).hasSize(1);
        assertThat(candidates.get(0).allocations().get(0).markPointId())
                .isEqualTo(partOne.points().get(0).id());
        assertThat(candidates.get(0).allocations().get(0).marksAwarded()).isEqualTo(2);
        assertThat(candidates.get(0).confidence()).isEqualTo(0.8);
        assertThat(candidates.get(1).allocations().get(0).markPointId())
                .isEqualTo(partTwo.points().get(0).id());
        assertThat(candidates.get(1).allocations().get(0).marksAwarded()).isEqualTo(1);
        assertThat(candidates.get(1).confidence()).isEqualTo(0.6);
        // every row keeps the FULL batch raw output — the audit trail is self-forensic
        assertThat(candidates.get(0).rawOutput()).isEqualTo(batchJson);
        assertThat(candidates.get(1).rawOutput()).isEqualTo(batchJson);
    }

    @Test
    @DisplayName("batch prompt renders numbered per-part sections and the guidance once")
    void batchUserPromptRendersPerPartSectionsAndGuidanceOnce() {
        String prompt = generator.batchUserPrompt(batchContextsWithGuidance());

        assertThat(prompt)
                .contains("STRUCTURED ATTEMPT: 2 answered parts")
                .contains("===== PART 1 of 2")
                .contains("===== PART 2 of 2")
                .contains("part a answer text")
                .contains("part b answer text");
        // guidance hoisted once — not duplicated per part
        assertThat(prompt.indexOf("SCHEME-LEVEL GENERAL INSTRUCTIONS"))
                .isEqualTo(prompt.lastIndexOf("SCHEME-LEVEL GENERAL INSTRUCTIONS"))
                .isLessThan(prompt.indexOf("===== PART 1 of 2"));
    }

    @Test
    @DisplayName("a batch response missing a part refuses the WHOLE batch (per-part fallback)")
    void parseBatchRefusesOnMissingPart() {
        List<MarkingContext> contexts = batchContexts(1, 1);
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith("""
                {"parts": [{"part": 1, "confidence": 0.9, "allocations": [
                  {"markPointId": "%s", "ref": "a-1", "marksAwarded": 1,
                   "evidence": "x", "rationale": "r"}]}]}
                """.formatted(contexts.get(0).points().get(0).id()));
        LlmMarkingCandidateGenerator batched = new LlmMarkingCandidateGenerator(chain);

        assertThatThrownBy(() -> batched.proposeAll(contexts))
                .isInstanceOf(CandidateGenerationException.class)
                .satisfies(e -> {
                    var refusal = (CandidateGenerationException) e;
                    assertThat(refusal.reason())
                            .isEqualTo(CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT);
                    assertThat(refusal.rawOutput()).contains("\"parts\"");
                });
    }

    @Test
    @DisplayName("a duplicated or out-of-range part number refuses the whole batch")
    void parseBatchRefusesOnBrokenPartNumbering() {
        List<MarkingContext> contexts = batchContexts(1, 1);
        String duplicated = """
                {"parts": [
                  {"part": 1, "confidence": 0.9, "allocations": [{"markPointId": "%s",
                   "ref": "a", "marksAwarded": 1, "evidence": "x", "rationale": "r"}]},
                  {"part": 1, "confidence": 0.9, "allocations": [{"markPointId": "%s",
                   "ref": "b", "marksAwarded": 1, "evidence": "x", "rationale": "r"}]}
                ]}
                """.formatted(contexts.get(0).points().get(0).id(),
                contexts.get(1).points().get(0).id());
        FakeLlmProvider duplicatedChain = FakeLlmProvider.named("groq")
                .respondsWith(duplicated);
        LlmMarkingCandidateGenerator broken = new LlmMarkingCandidateGenerator(duplicatedChain);
        assertThatThrownBy(() -> broken.proposeAll(contexts))
                .isInstanceOf(CandidateGenerationException.class)
                .satisfies(e -> assertThat(((CandidateGenerationException) e).reason())
                        .isEqualTo(CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT));

        String outOfRange = """
                {"parts": [
                  {"part": 1, "confidence": 0.9, "allocations": [{"markPointId": "%s",
                   "ref": "a", "marksAwarded": 1, "evidence": "x", "rationale": "r"}]},
                  {"part": 7, "confidence": 0.9, "allocations": [{"markPointId": "%s",
                   "ref": "b", "marksAwarded": 1, "evidence": "x", "rationale": "r"}]}
                ]}
                """.formatted(contexts.get(0).points().get(0).id(),
                contexts.get(1).points().get(0).id());
        FakeLlmProvider rangeChain = FakeLlmProvider.named("groq").respondsWith(outOfRange);
        LlmMarkingCandidateGenerator ranging = new LlmMarkingCandidateGenerator(rangeChain);
        assertThatThrownBy(() -> ranging.proposeAll(contexts))
                .isInstanceOf(CandidateGenerationException.class);
    }

    @Test
    @DisplayName("batch parse aligns candidates by the DECLARED part number, not array order")
    void parseBatchAlignsByDeclaredPartNumber() {
        List<MarkingContext> contexts = batchContexts(1, 1);
        MarkingContext partOne = contexts.get(0);
        MarkingContext partTwo = contexts.get(1);
        String reordered = """
                {"parts": [
                  {"part": 2, "confidence": 0.7, "allocations": [{"markPointId": "%s",
                   "ref": "b-1", "marksAwarded": 1, "evidence": "b quote", "rationale": "r"}]},
                  {"part": 1, "confidence": 0.9, "allocations": [{"markPointId": "%s",
                   "ref": "a-1", "marksAwarded": 0, "evidence": "", "rationale": "r"}]}
                ]}
                """.formatted(partTwo.points().get(0).id(), partOne.points().get(0).id());
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith(reordered);
        LlmMarkingCandidateGenerator batched = new LlmMarkingCandidateGenerator(chain);

        List<MarkingCandidate> candidates = batched.proposeAll(contexts);

        assertThat(candidates.get(0).allocations().get(0).markPointId())
                .isEqualTo(partOne.points().get(0).id());
        assertThat(candidates.get(0).allocations().get(0).marksAwarded()).isZero();
        assertThat(candidates.get(1).allocations().get(0).markPointId())
                .isEqualTo(partTwo.points().get(0).id());
        assertThat(candidates.get(1).allocations().get(0).marksAwarded()).isEqualTo(1);
    }

    @Test
    @DisplayName("a budget-truncated batch refuses as TRUNCATED_OUTPUT carrying the raw text")
    void truncatedBatchRefusesWithRawOutput() {
        List<MarkingContext> contexts = batchContexts(1, 1);
        FakeLlmProvider chain = FakeLlmProvider.named("groq").respondsWith(new LlmResponse(
                "{\"parts\": [{\"part\": 1, \"alloc",
                "groq", "fake-model", 5L, 10, 10, "length"));
        LlmMarkingCandidateGenerator truncated = new LlmMarkingCandidateGenerator(chain);

        assertThatThrownBy(() -> truncated.proposeAll(contexts))
                .isInstanceOf(CandidateGenerationException.class)
                .satisfies(e -> {
                    var refusal = (CandidateGenerationException) e;
                    assertThat(refusal.reason())
                            .isEqualTo(CandidateGenerationException.Reason.TRUNCATED_OUTPUT);
                    assertThat(refusal.rawOutput()).startsWith("{\"parts\"");
                });
    }

    private static MarkingContext context(String guidance) {
        return context(guidance, 1);
    }

    /**
     * A two-part batch fixture: one attempt, one shared scheme, two answered
     * parts each carrying exactly one point of the given worth — the shapes the
     * v4 batch tests reason over.
     */
    private static List<MarkingContext> batchContexts(int partOnePointMarks,
                                                      int partTwoPointMarks) {
        return batchContexts(partOnePointMarks, partTwoPointMarks, null);
    }

    private static List<MarkingContext> batchContextsWithGuidance() {
        return batchContexts(1, 2, "Accept ecf. Ignore significant figure penalties.");
    }

    private static List<MarkingContext> batchContexts(int partOnePointMarks,
                                                      int partTwoPointMarks,
                                                      String guidance) {
        Question question = new Question("q-batch", Question.Type.STRUCTURED, "stem", 5, 3,
                120, "Explain", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 5, 3, 120,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        QuestionPart partA = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(partA, UUID.randomUUID());
        QuestionPart partB = new QuestionPart(version, "b", "part b", "State", 3, 1);
        TestIds.withId(partB, UUID.randomUUID());
        version.addPart(partA);
        version.addPart(partB);

        var attempt = new com.syllabai.assessment.Attempt(UUID.randomUUID(), question,
                null, false, null, 5000L, 4, false, true, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        Answer answerA = new Answer(attempt, partA, "part a answer text");
        TestIds.withId(answerA, UUID.randomUUID());
        Answer answerB = new Answer(attempt, partB, "part b answer text");
        TestIds.withId(answerB, UUID.randomUUID());

        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        scheme.setGeneralGuidance(guidance);
        MarkPoint pointA = new MarkPoint(scheme, partA, "a-1", 0, "iron oxide",
                partOnePointMarks, List.of(), 0.9);
        TestIds.withId(pointA, UUID.randomUUID());
        MarkPoint pointB = new MarkPoint(scheme, partB, "b-1", 0, "water",
                partTwoPointMarks, List.of(), 0.9);
        TestIds.withId(pointB, UUID.randomUUID());
        scheme.addPoint(pointA);
        scheme.addPoint(pointB);

        return List.of(
                new MarkingContext(answerA, partA, scheme, List.of(pointA)),
                new MarkingContext(answerB, partB, scheme, List.of(pointB)));
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
