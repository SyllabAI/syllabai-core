package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Smart Mark pipeline core (Master Spec §15): normalization → scheme scope →
 * candidate generation → deterministic validation → decision. Pure orchestration:
 * persistence, evidence and telemetry live in {@link SmartMarkService}; this class
 * owns the go/no-go decision and the explainable breakdown.
 *
 * <p>Blank answers short-circuit to a deterministic zero-mark decision — no LLM call,
 * no cost, no hallucination surface.</p>
 */
@Component
public class SmartMarkPipeline {

    private static final Logger log = LoggerFactory.getLogger(SmartMarkPipeline.class);

    private final MarkingCandidateGenerator generator;
    private final List<MarkingValidator> validators;

    /**
     * Factory wiring (§23): the validator chain is injected as an ordered list of
     * Strategy implementations; adding a validator is a new @Component, never an edit.
     */
    public SmartMarkPipeline(MarkingCandidateGenerator generator,
                             List<MarkingValidator> validators) {
        this.generator = generator;
        this.validators = List.copyOf(validators);
    }

    /**
     * Outcome of one pipeline run.
     *
     * @param accepted          validators passed (marks are provisional, never final)
     * @param marksAwarded      sum of awarded point marks (0 when rejected/failed)
     * @param breakdown         per-point decision records for persistence + κ pairing
     * @param failureReason     stable code when not accepted
     * @param candidate         the raw candidate (null when short-circuited/failed)
     */
    public record Decision(boolean accepted, int marksAwarded,
                           List<Map<String, Object>> breakdown,
                           String failureReason, MarkingCandidate candidate) {

        public static Decision rejected(String reason, MarkingCandidate candidate) {
            return new Decision(false, 0, List.of(), reason, candidate);
        }
    }

    public Decision run(MarkingContext context) {
        List<MarkPoint> points = context.points();
        if (points.isEmpty()) {
            return Decision.rejected("NO_SCHEME_POINTS", null);
        }
        String answerText = context.answer().answerText();
        if (answerText == null || answerText.isBlank()) {
            // deterministic short-circuit: no evidence → no marks, no LLM call
            return new Decision(true, 0, blankBreakdown(points), null, null);
        }

        MarkingCandidate candidate;
        try {
            candidate = generator.propose(context);
        } catch (CandidateGenerationException e) {
            log.info("candidate generation failed: {} ({})", e.getMessage(), e.reason());
            return Decision.rejected(e.reason().name(), null);
        }

        List<String> violations = new java.util.ArrayList<>();
        for (MarkingValidator validator : validators) {
            violations.addAll(validator.validate(candidate, context));
        }
        if (!violations.isEmpty()) {
            log.info("candidate rejected by validators: {}", violations);
            String joined = String.join("; ", violations);
            String reason = joined.length() > 190
                    ? joined.substring(0, 190) : joined;
            return Decision.rejected("VALIDATION_FAILED: " + reason, candidate);
        }

        Map<UUID, MarkPoint> byId = points.stream()
                .collect(java.util.stream.Collectors.toMap(MarkPoint::id, p -> p));
        List<Map<String, Object>> breakdown = new java.util.ArrayList<>();
        int awarded = 0;
        for (MarkingCandidate.Allocation allocation : candidate.allocations()) {
            MarkPoint point = byId.get(allocation.markPointId());
            if (allocation.awarded()) {
                awarded += point.marks();
            }
            breakdown.add(Map.ofEntries(
                    Map.entry("markPointId", allocation.markPointId().toString()),
                    Map.entry("ref", String.valueOf(allocation.ref())),
                    Map.entry("marks", point.marks()),
                    Map.entry("awarded", allocation.awarded()),
                    Map.entry("evidence", String.valueOf(allocation.evidence())),
                    Map.entry("rationale", String.valueOf(allocation.rationale()))));
        }
        return new Decision(true, awarded, List.copyOf(breakdown), null, candidate);
    }

    private static List<Map<String, Object>> blankBreakdown(List<MarkPoint> points) {
        return points.stream()
                .map(p -> Map.<String, Object>ofEntries(
                        Map.entry("markPointId", p.id().toString()),
                        Map.entry("ref", String.valueOf(p.ref())),
                        Map.entry("marks", p.marks()),
                        Map.entry("awarded", false),
                        Map.entry("evidence", ""),
                        Map.entry("rationale", "blank answer: deterministic zero")))
                .toList();
    }

    /** answer confidence reported by the generator (null-safe) */
    public Double candidateConfidence(Decision decision) {
        return decision.candidate() == null ? null : decision.candidate().confidence();
    }

    /** verbatim generator output for the audit trail (null-safe) */
    public String candidateRawOutput(Decision decision) {
        return decision.candidate() == null ? null : decision.candidate().rawOutput();
    }

    public String candidateModelId(Decision decision) {
        return decision.candidate() == null ? null : decision.candidate().modelId();
    }
}
