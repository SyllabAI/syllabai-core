package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smart Mark pipeline (Master Spec §15): blank answers short-circuit, accepted
 * candidates award exactly the validated points, and rejected candidates (bounds /
 * coverage / mark-sum violations, generator failures) never award anything.
 */
class SmartMarkPipelineTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();

    private final Answer answer;
    private final QuestionPart part;
    private final MarkScheme scheme;
    private final MarkPoint pointA;
    private final MarkPoint pointB;
    private MarkingCandidateGenerator generator;
    private final SmartMarkPipeline pipeline = new SmartMarkPipeline(
            ctx -> {
                if (generator == null) {
                    throw new CandidateGenerationException(
                            CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                            "no generator configured", null);
                }
                return generator.propose(ctx);
            },
            List.of(new BoundsMarkingValidator(), new CoverageMarkingValidator(),
                    new MarkSumMarkingValidator()));

    SmartMarkPipelineTest() {
        Question question = new Question("q-1", Question.Type.STRUCTURED, "stem", 4, 3, 240,
                "Explain", TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 4, 3, 240,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        part = new QuestionPart(version, "a", "part a", "State", 3, 0);
        TestIds.withId(part, UUID.randomUUID());
        version.addPart(part);

        Attempt attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, true, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answer = new Answer(attempt, part, "an answer mentioning iron(III) oxide");
        TestIds.withId(answer, UUID.randomUUID());

        scheme = new MarkScheme(version, "1", "ms-doc", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        pointA = new MarkPoint(scheme, part, "1-a", 0, "iron(III) oxide named", 1,
                List.of("Fe2O3", "iron(III) oxide"), 0.9);
        TestIds.withId(pointA, UUID.randomUUID());
        pointB = new MarkPoint(scheme, part, "1-a", 1, "water named", 1,
                List.of("H2O", "water"), 0.9);
        TestIds.withId(pointB, UUID.randomUUID());
        scheme.addPoint(pointA);
        scheme.addPoint(pointB);

        // default generator stub is replaced per-test
    }

    private MarkingContext context() {
        return new MarkingContext(answer, part, scheme, List.of(pointA, pointB));
    }

    @Test
    @DisplayName("blank answer short-circuits: zero marks, no LLM call")
    void blankAnswerShortCircuits() {
        Answer blank = new Answer(answer.attempt(), part, "   ");
        TestIds.withId(blank, UUID.randomUUID());
        MarkingCandidateGenerator throwing = new MarkingCandidateGenerator() {
            @Override
            public MarkingCandidate propose(MarkingContext ctx) {
                throw new AssertionError("LLM must not be called for blank answers");
            }
        };
        SmartMarkPipeline p = new SmartMarkPipeline(throwing,
                List.of(new BoundsMarkingValidator(), new CoverageMarkingValidator(),
                        new MarkSumMarkingValidator()));
        var decision = p.run(new MarkingContext(blank, part, scheme,
                List.of(pointA, pointB)));
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.marksAwarded()).isZero();
        assertThat(decision.breakdown()).hasSize(2);
        assertThat(decision.breakdown()).allSatisfy(
                b -> assertThat(b.get("rationale")).isEqualTo("blank answer: deterministic zero"));
    }

    @Test
    @DisplayName("accepted candidate awards validated point marks with evidence")
    void acceptedCandidateAwards() {
        generator = ctx -> new MarkingCandidate("test-model", List.of(
                new MarkingCandidate.Allocation(pointA.id(), "1-a", true,
                        "iron(III) oxide", "names the oxide"),
                new MarkingCandidate.Allocation(pointB.id(), "1-a", false, "", "not mentioned")),
                0.85, "raw");
        var decision = pipeline.run(new MarkingContext(answer, part,
                scheme, List.of(pointA, pointB)));
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.marksAwarded()).isEqualTo(1);
        assertThat(decision.breakdown()).hasSize(2);
        assertThat(decision.breakdown().get(0).get("awarded")).isEqualTo(true);
        assertThat(decision.breakdown().get(1).get("awarded")).isEqualTo(false);
    }

    @Test
    @DisplayName("coverage violation (undecided point) rejects the candidate")
    void coverageViolationRejects() {
        generator = ctx -> new MarkingCandidate("test-model", List.of(
                new MarkingCandidate.Allocation(pointA.id(), "1-a", true, "x", "y")),
                0.9, "raw");
        var decision = pipeline.run(new MarkingContext(answer, part,
                scheme, List.of(pointA, pointB)));
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.marksAwarded()).isZero();
        assertThat(decision.failureReason()).contains("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("bounds violation (invented point id) rejects the candidate")
    void boundsViolationRejects() {
        UUID invented = UUID.randomUUID();
        generator = ctx -> new MarkingCandidate("test-model", List.of(
                new MarkingCandidate.Allocation(invented, "??", true, "x", "y"),
                new MarkingCandidate.Allocation(pointB.id(), "1-a", false, "", "")),
                0.9, "raw");
        var decision = pipeline.run(new MarkingContext(answer, part,
                scheme, List.of(pointA, pointB)));
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.failureReason()).contains("unknown mark point");
    }

    @Test
    @DisplayName("mark-sum violation (awarding more than the part allows) rejects")
    void markSumViolationRejects() {
        // part marks = 3 but scheme offers 5 -> bound = min(3, 5) = 3; award 5
        Question question = new Question("q-2", Question.Type.STRUCTURED, "stem", 5, 3, 240,
                "Explain", TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 5, 3, 240,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        QuestionPart p2 = new QuestionPart(version, "a", "part a", "State", 1, 0);
        TestIds.withId(p2, UUID.randomUUID());
        version.addPart(p2);
        Attempt attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, false, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        Answer a2 = new Answer(attempt, p2, "answer");
        TestIds.withId(a2, UUID.randomUUID());

        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        MarkPoint big = new MarkPoint(scheme, p2, "q-a", 0, "big point", 5, List.of(), 0.9);
        TestIds.withId(big, UUID.randomUUID());
        scheme.addPoint(big);

        generator = ctx -> new MarkingCandidate("test-model", List.of(
                new MarkingCandidate.Allocation(big.id(), "q-a", true, "x", "y")),
                0.9, "raw");
        var decision = pipeline.run(new MarkingContext(a2, p2, scheme, List.of(big)));
        // part marks 1, scheme ceiling 5 -> bound 1; awarded 5 > 1 -> reject
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.failureReason()).contains("exceeds scheme bound");
    }

    @Test
    @DisplayName("generator failure records a failed run, never fabricates marks")
    void generatorFailureRecordsFailure() {
        generator = ctx -> {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE, "chain down", null);
        };
        var decision = pipeline.run(new MarkingContext(answer, part,
                scheme, List.of(pointA, pointB)));
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.marksAwarded()).isZero();
        assertThat(decision.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("no scheme points in scope fails loudly")
    void noPointsFails() {
        var decision = pipeline.run(new MarkingContext(answer, part,
                scheme, List.of()));
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.failureReason()).isEqualTo("NO_SCHEME_POINTS");
    }
}
