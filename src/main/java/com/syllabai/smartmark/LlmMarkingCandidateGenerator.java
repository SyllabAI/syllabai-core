package com.syllabai.smartmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM-backed candidate generator (Master Spec §15, §26): composes a marking prompt
 * from the part, the scheme's mark points and the learner answer, asks the free-LLM
 * chain for a strict-JSON allocation, and parses it into a {@link MarkingCandidate}.
 *
 * <p>Everything the model returns is a <em>candidate</em>: unknown point ids, missing
 * decisions and impossible mark sums are caught downstream by the deterministic
 * validators — this adapter trusts nothing it cannot parse.</p>
 *
 * <p>Temperature is pinned to 0.1 for near-deterministic marking decisions; the
 * prompt is versioned in the {@code prompt_versions} registry
 * ({@code smart-mark-candidate}, v3 — V36 seed). v2 added the scheme-level
 * general-guidance section ("accept ecf", "ignore significant-figure penalties").
 * v3 replaces boolean whole-point awards with per-point partial marks: SME
 * schemes bundle several examiner sub-points ("[1 mark]" annotations) into one
 * multi-mark row, and all-or-nothing allocation denied partial credit on
 * ~1,877 corpus parts (operator scenario 2026-09-21: a 3-mark point scored 0
 * when the learner had earned the squeaky-pop sub-point — deserved 1/3).</p>
 */
@Component
public class LlmMarkingCandidateGenerator implements MarkingCandidateGenerator {

    public static final String PROMPT_REGISTRY_KEY = "smart-mark-candidate";
    public static final String PROMPT_VERSION = "3";

    private static final Logger log = LoggerFactory.getLogger(LlmMarkingCandidateGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /*
     * Completion budget (G-4 round 2026-09-22, UNPARSEABLE_OUTPUT_INVESTIGATION):
     * the marking call previously pinned a flat 800-token cap. The deployed
     * default model is a REASONING model — reasoning tokens share the completion
     * budget with the visible JSON — and prompt v3 asks it to assess every
     * sub-point of a compound point independently. The round's only refusal (the
     * reasoning-heaviest compound point, a 5-mark "explain why" with the largest
     * single scheme-point text) REPRODUCED on re-run: reasoning + JSON cannot
     * reliably fit 800 for such points, so the budget now scales with the
     * in-scope point marks and every completion's stop cause is inspected (a
     * budget-truncated completion refuses as TRUNCATED_OUTPUT carrying the raw
     * text, not as a generic parse failure).
     */
    private static final int BASE_COMPLETION_BUDGET = 800;
    private static final int COMPLETION_BUDGET_PER_MARK = 400;
    private static final int MAX_COMPLETION_BUDGET = 4_000;

    private final LlmProvider chain;

    public LlmMarkingCandidateGenerator(LlmProvider chain) {
        // constructor injection of the port — the chain (groq → gemini → openrouter)
        this.chain = chain;
    }

    @Override
    public MarkingCandidate propose(MarkingContext context) {
        if (!chain.available()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                    "LLM chain unavailable for smart marking", null);
        }

        LlmResponse response;
        try {
            response = chain.generate(LlmRequest.withOptions(
                    systemPrompt(), userPrompt(context), 0.1,
                    completionBudget(context.points())));
        } catch (LlmProviderException e) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                    "LLM chain failed during marking: " + e.getMessage(), e);
        }

        if (isTruncationFinish(response.finishReason())) {
            // the completion hit the token cap mid-flight: whatever text arrived is
            // an incomplete payload, not a candidate — refuse with the raw text
            // attached so the row is self-forensic (never silently re-marked)
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.TRUNCATED_OUTPUT,
                    "generator completion hit the token budget (finish_reason="
                            + response.finishReason() + ")",
                    null, response.text());
        }

        return parse(response.text(), context, response.model());
    }

    /**
     * Completion budget for one marking call: the flat historical floor plus
     * headroom per in-scope point mark (compound multi-mark points need
     * reasoning + per-sub-point rationale space), capped so a pathological
     * scheme cannot balloon the call.
     */
    static int completionBudget(List<MarkPoint> points) {
        int totalMarks = 0;
        for (MarkPoint point : points) {
            totalMarks += Math.max(1, point.marks());
        }
        return Math.min(BASE_COMPLETION_BUDGET + COMPLETION_BUDGET_PER_MARK * totalMarks,
                MAX_COMPLETION_BUDGET);
    }

    /**
     * OpenAI-compatible truncation stop causes (normalized lower-case): the
     * completion was cut by the token budget. {@code max_tokens} covers the
     * Google GenAI spelling of the same cause.
     */
    static boolean isTruncationFinish(String finishReason) {
        if (finishReason == null) {
            return false;
        }
        String normalized = finishReason.strip().toLowerCase();
        return normalized.equals("length") || normalized.equals("max_tokens");
    }

    String systemPrompt() {
        return """
                You are an exam marker aligned strictly to the provided mark scheme.
                For every MARK POINT decide how many of its marks the learner earns:
                - A point worth N marks may bundle several sub-points, each annotated
                  like \"[1 mark]\" or listed as separate bullets. Assess every
                  sub-point INDEPENDENTLY and return the sum the learner earned
                  (0 up to N).
                - Award a sub-point when the learner's answer contains its required
                  content (spelling variants allowed when the scheme says so).
                  Missing one sub-point never blocks another, unless the scheme
                  states a dependency (e.g. \"dep on M1\").
                - \"evidence\": the shortest verbatim quote from the learner answer
                  that justifies the marks earned (\"\" when none earned).
                - \"rationale\": one or two short sentences; for a multi-mark point,
                  name which sub-points were earned and which were missed.
                Respond with ONLY a JSON object:
                {\"confidence\": <0..1>, \"allocations\": [{\"markPointId\": \"<id>\",
                \"ref\": \"<ref>\", \"marksAwarded\": <0..N>, \"evidence\": \"...\",
                \"rationale\": \"...\"}]}
                Decide EVERY listed mark point. Never invent mark point ids. Never
                award more marks than a point is worth.
                """;
    }

    String userPrompt(MarkingContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION PART (").append(context.part().label()).append("):\n")
                .append(context.part().prompt()).append("\n\nMARK SCHEME POINTS:\n");
        for (MarkPoint point : context.points()) {
            sb.append("- id=").append(point.id())
                    .append(" ref=").append(point.ref() == null ? "?" : point.ref())
                    .append(" marks=").append(point.marks())
                    .append("\n  required: ").append(point.text()).append('\n');
            if (!point.acceptanceCriteria().isEmpty()) {
                sb.append("  acceptance: ").append(String.join("; ", point.acceptanceCriteria()))
                        .append('\n');
            }
        }
        String guidance = context.scheme() == null ? null : context.scheme().generalGuidance();
        if (guidance != null && !guidance.isBlank()) {
            sb.append("\nSCHEME-LEVEL GENERAL INSTRUCTIONS (board-issued, apply to every\n")
                    .append("decision below, e.g. accept ecf / ignore penalties):\n")
                    .append(guidance.strip()).append('\n');
        }
        sb.append("\nLEARNER ANSWER:\n").append(context.answer().answerText());
        return sb.toString();
    }

    MarkingCandidate parse(String raw, MarkingContext context, String modelId) {
        String body = extractJsonBody(raw);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    "generator output is not valid JSON", e, raw);
        }
        JsonNode allocations = root.get("allocations");
        if (allocations == null || !allocations.isArray()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    "generator output missing allocations array", null, raw);
        }
        Map<UUID, MarkPoint> pointsById = context.points().stream()
                .collect(java.util.stream.Collectors.toMap(MarkPoint::id, p -> p));
        List<MarkingCandidate.Allocation> parsed = new ArrayList<>();
        for (JsonNode node : allocations) {
            String id = textOf(node, "markPointId");
            if (id == null || id.isBlank()) {
                throw new CandidateGenerationException(
                        CandidateGenerationException.Reason.MALFORMED_ALLOCATION,
                        "allocation without markPointId", null);
            }
            java.util.UUID markPointId;
            try {
                markPointId = java.util.UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                // a malformed id is a bad CANDIDATE, not a server error — reject
                // it through the normal candidate-exception path instead of
                // letting IllegalArgumentException surface as an HTTP 400
                throw new CandidateGenerationException(
                        CandidateGenerationException.Reason.MALFORMED_ALLOCATION,
                        "allocation markPointId is not a UUID: " + id, null);
            }
            parsed.add(new MarkingCandidate.Allocation(
                    markPointId,
                    textOf(node, "ref"),
                    resolveMarks(node, pointsById.get(markPointId)),
                    textOf(node, "evidence"),
                    textOf(node, "rationale")));
        }
        Double confidence = nodeDouble(root.get("confidence"));
        log.debug("parsed {} candidate allocations from model {}", parsed.size(), modelId);
        return new MarkingCandidate(modelId, List.copyOf(parsed), confidence, raw);
    }

    /**
     * v3 allocation marks: the model returns {@code marksAwarded} (0..N) per
     * point; an over-award is an arithmetic slip, not a hallucination — clamp,
     * never reject (rejection would drop the whole part to the teacher queue).
     * Backward compatibility: a model that answers the v2 shape (boolean
     * {@code awarded} only) still parses — boolean semantics award the whole
     * point or nothing, exactly what v2 meant.
     */
    static int resolveMarks(JsonNode node, MarkPoint point) {
        int pointMarks = point == null ? 1 : Math.max(1, point.marks());
        JsonNode marks = node.get("marksAwarded");
        if (marks != null && marks.isNumber()) {
            return Math.max(0, Math.min(marks.asInt(), pointMarks));
        }
        return node.path("awarded").asBoolean(false) ? pointMarks : 0;
    }

    private static String extractJsonBody(String raw) {
        if (raw == null) throw new CandidateGenerationException(
                CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT, "null output", null);
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) throw new CandidateGenerationException(
                CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                "no JSON object in output", null);
        return raw.substring(start, end + 1);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double nodeDouble(JsonNode node) {
        return node == null || node.isNull() ? null : node.asDouble();
    }
}
