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
            // self-forensic refusals: when the generator carried raw provider output
            // (e.g. a budget-truncated completion), persist it for audit instead of
            // discarding it — the failure row then explains itself without server
            // logs (G-4 round 2026-09-22 finding). The decision stays rejected
            // either way: fail-closed semantics are untouched.
            return e.rawOutput() == null
                    ? Decision.rejected(e.reason().name(), null)
                    : Decision.rejected(e.reason().name(),
                            new MarkingCandidate(null, List.of(), null, e.rawOutput()));
        }
        return decide(candidate, context);
    }

    /**
     * Mark a whole attempt's parts and return one Decision per context,
     * index-aligned with the input. Deterministic short-circuits (no scheme
     * points, blank answers) never reach the generator; the rest goes through
     * {@link MarkingCandidateGenerator#proposeAll} — ONE provider call for the
     * attempt (v4 batch topology) — and each candidate is validated against its
     * own context exactly as in {@link #run}.
     *
     * <p>Fallback ladder: any batch-generation failure (provider unavailable,
     * truncated, unparseable — the batch refuses as a whole) re-marks the
     * markable contexts per part through {@link #run}, so one flaky batch call
     * degrades to exactly the pre-batching behaviour and every part keeps its
     * independent failure semantics. A generator whose batch result is not
     * index-aligned (broken implementation, never the shipped one) is treated
     * the same way: refuse the batch, fall back, never guess.</p>
     */
    public List<Decision> runBatch(List<MarkingContext> contexts) {
        List<Decision> decisions = new java.util.ArrayList<>(
                java.util.Collections.nCopies(contexts.size(), (Decision) null));
        List<Integer> markable = new java.util.ArrayList<>();
        for (int i = 0; i < contexts.size(); i++) {
            MarkingContext context = contexts.get(i);
            if (context.points().isEmpty()) {
                decisions.set(i, Decision.rejected("NO_SCHEME_POINTS", null));
            } else {
                String answerText = context.answer().answerText();
                if (answerText == null || answerText.isBlank()) {
                    // deterministic short-circuit: no evidence → no marks, no LLM call
                    decisions.set(i, new Decision(true, 0,
                            blankBreakdown(context.points()), null, null));
                } else {
                    markable.add(i);
                }
            }
        }
        if (markable.isEmpty()) {
            return List.copyOf(decisions);
        }

        List<MarkingContext> batchContexts = markable.stream().map(contexts::get).toList();
        try {
            List<MarkingCandidate> candidates = generator.proposeAll(batchContexts);
            if (candidates.size() != batchContexts.size()) {
                throw new CandidateGenerationException(
                        CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                        "batch generator returned " + candidates.size() + " candidates for "
                                + batchContexts.size() + " contexts", null);
            }
            for (int j = 0; j < markable.size(); j++) {
                decisions.set(markable.get(j), decide(candidates.get(j), batchContexts.get(j)));
            }
            return List.copyOf(decisions);
        } catch (CandidateGenerationException e) {
            log.info("batch candidate generation failed ({}); falling back to per-part marking",
                    e.getMessage());
            for (int i = 0; i < markable.size(); i++) {
                decisions.set(markable.get(i), run(batchContexts.get(i)));
            }
            return List.copyOf(decisions);
        }
    }

    /**
     * Deterministic validation + breakdown for one candidate against its own
     * context — the stage after generation, shared byte-for-byte by the
     * single-part and batch topologies so the go/no-go semantics can never
     * drift between them.
     */
    private Decision decide(MarkingCandidate candidate, MarkingContext context) {
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

        Map<UUID, MarkPoint> byId = context.points().stream()
                .collect(java.util.stream.Collectors.toMap(MarkPoint::id, p -> p));
        List<Map<String, Object>> breakdown = new java.util.ArrayList<>();
        int awarded = 0;
        for (MarkingCandidate.Allocation allocation : candidate.allocations()) {
            MarkPoint point = byId.get(allocation.markPointId());
            // per-point partial marks (v3): a multi-mark point may award a subset —
            // clamp defensively so a candidate can never exceed the point's worth
            int resolved = point == null ? 0
                    : Math.min(allocation.marksAwarded(), point.marks());
            awarded += Math.max(0, resolved);
            breakdown.add(Map.ofEntries(
                    Map.entry("markPointId", allocation.markPointId().toString()),
                    Map.entry("ref", String.valueOf(allocation.ref())),
                    Map.entry("marks", point.marks()),
                    Map.entry("marksAwarded", Math.max(0, resolved)),
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
                        Map.entry("marksAwarded", 0),
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
