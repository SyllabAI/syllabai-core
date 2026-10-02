package com.syllabai.smartmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p>v4 adds the attempt-batch topology (single-call marking): {@link
 * #proposeAll} marks every answered part of one attempt in ONE provider call —
 * same marking rules, same per-point partial-marks semantics, only the
 * transport envelope changes ({@code "parts": [...]} keyed by 1-based part
 * number). A truncated or unparseable batch response refuses as a whole and the
 * pipeline falls back to the classic per-part {@link #propose} calls, so the
 * batch fast path can never lower marking availability.</p>
 */
@Component
public class LlmMarkingCandidateGenerator implements MarkingCandidateGenerator {

    public static final String PROMPT_REGISTRY_KEY = "smart-mark-candidate";
    public static final String PROMPT_VERSION = "3";

    /** v4 = the attempt-batch envelope (single call marks all of an attempt's parts). */
    public static final String BATCH_PROMPT_VERSION = "4";

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
    /*
     * Batch cap: one call replaces K per-part calls, so the pathological-case
     * ceiling scales with the WHOLE attempt, not one part. The linear
     * 800 + 400/mark formula already grows with total marks; 8_000 leaves
     * headroom to an ~18-mark attempt (the heaviest IGCSE structured questions
     * total 10-12) before truncation — and a truncated batch still refuses
     * honestly into the per-part fallback, never into fabricated marks.
     */
    private static final int MAX_BATCH_COMPLETION_BUDGET = 8_000;

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
     * Single-call attempt batching (prompt v4): ONE provider call proposes
     * allocations for every markable context of the attempt. Truncation and
     * parse failures refuse for the WHOLE batch — the pipeline's fallback ladder
     * then re-marks per part with the classic v3 topology, so a flaky batch call
     * degrades to exactly the pre-batching behaviour instead of failing every
     * part.
     */
    @Override
    public List<MarkingCandidate> proposeAll(List<MarkingContext> contexts) {
        if (!chain.available()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                    "LLM chain unavailable for smart marking", null);
        }

        LlmResponse response;
        try {
            response = chain.generate(LlmRequest.withOptions(
                    batchSystemPrompt(), batchUserPrompt(contexts), 0.1,
                    batchCompletionBudget(contexts)));
        } catch (LlmProviderException e) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                    "LLM chain failed during batch marking: " + e.getMessage(), e);
        }

        if (isTruncationFinish(response.finishReason())) {
            // same self-forensic contract as the single-part path: a
            // budget-truncated batch is an incomplete payload, not a candidate
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.TRUNCATED_OUTPUT,
                    "batch generator completion hit the token budget (finish_reason="
                            + response.finishReason() + ")",
                    null, response.text());
        }

        return parseBatch(response.text(), contexts, response.model());
    }

    /**
     * Completion budget for one marking call: the flat historical floor plus
     * headroom per in-scope point mark (compound multi-mark points need
     * reasoning + per-sub-point rationale space), capped so a pathological
     * scheme cannot balloon the call.
     */
    static int completionBudget(List<MarkPoint> points) {
        return completionBudgetFor(sumMarks(points), MAX_COMPLETION_BUDGET);
    }

    /**
     * Completion budget for one attempt-batch call: the same linear formula
     * applied across every in-scope point of every part, with the batch
     * ceiling ({@link #MAX_BATCH_COMPLETION_BUDGET}) — the whole attempt shares
     * one budget because the parts share one completion.
     */
    static int batchCompletionBudget(List<MarkingContext> contexts) {
        int totalMarks = 0;
        for (MarkingContext context : contexts) {
            totalMarks += sumMarks(context.points());
        }
        return completionBudgetFor(totalMarks, MAX_BATCH_COMPLETION_BUDGET);
    }

    private static int sumMarks(List<MarkPoint> points) {
        int totalMarks = 0;
        for (MarkPoint point : points) {
            totalMarks += Math.max(1, point.marks());
        }
        return totalMarks;
    }

    private static int completionBudgetFor(int totalMarks, int cap) {
        return Math.min(BASE_COMPLETION_BUDGET + COMPLETION_BUDGET_PER_MARK * totalMarks, cap);
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

    /**
     * The marking rules — byte-identical between the single-part (v3) and the
     * attempt-batch (v4) system prompts; only the response envelope differs.
     */
    private static final String MARKING_RULES = """
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
            """;

    String systemPrompt() {
        return MARKING_RULES + """
                Respond with ONLY a JSON object:
                {\"confidence\": <0..1>, \"allocations\": [{\"markPointId\": \"<id>\",
                \"ref\": \"<ref>\", \"marksAwarded\": <0..N>, \"evidence\": \"...\",
                \"rationale\": \"...\"}]}
                Decide EVERY listed mark point. Never invent mark point ids. Never
                award more marks than a point is worth.
                """;
    }

    /** v4 batch envelope: one {@code parts} array, one object per answered part. */
    String batchSystemPrompt() {
        return MARKING_RULES + """
                The learner answered SEVERAL question parts of one question. Mark
                each part INDEPENDENTLY against its own listed mark points — an
                answer to one part never earns another part's marks.
                Respond with ONLY a JSON object:
                {"parts": [{"part": <partNumber>, "confidence": <0..1>,
                "allocations": [{"markPointId": "<id>", "ref": "<ref>",
                "marksAwarded": <0..N>, "evidence": "...", "rationale": "..."}]}]}
                <partNumber> is the 1-based number printed in the PART header.
                Return one object for EVERY listed part. Decide EVERY listed mark
                point of every part. Never invent mark point ids. Never award more
                marks than a point is worth.
                """;
    }

    String userPrompt(MarkingContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION PART (").append(context.part().label()).append("):\n")
                .append(context.part().prompt()).append("\n\nMARK SCHEME POINTS:\n");
        appendSchemePoints(sb, context.points());
        appendGeneralGuidance(sb, context.scheme());
        appendLearnerAnswer(sb, context.answer());
        return sb.toString();
    }

    /**
     * v4 batch user prompt: the scheme's general guidance once (one attempt =
     * one question version = one scheme, the {@link #proposeAll} contract), then
     * each part's section rendered exactly like the single-part prompt — header,
     * its own scheme points, its own learner answer — under a numbered PART
     * header the batch envelope's {@code part} field refers to.
     */
    String batchUserPrompt(List<MarkingContext> contexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("STRUCTURED ATTEMPT: ").append(contexts.size())
                .append(" answered parts of one question, each marked against its\n")
                .append("own listed mark points. Mark every part below.\n");
        appendGeneralGuidance(sb, contexts.get(0).scheme());
        for (int i = 0; i < contexts.size(); i++) {
            MarkingContext context = contexts.get(i);
            sb.append("\n===== PART ").append(i + 1).append(" of ").append(contexts.size())
                    .append(" (label: ").append(context.part().label()).append(") =====\n")
                    .append("QUESTION PART (").append(context.part().label()).append("):\n")
                    .append(context.part().prompt()).append("\n\nMARK SCHEME POINTS:\n");
            appendSchemePoints(sb, context.points());
            appendLearnerAnswer(sb, context.answer());
        }
        return sb.toString();
    }

    private static void appendSchemePoints(StringBuilder sb, List<MarkPoint> points) {
        for (MarkPoint point : points) {
            sb.append("- id=").append(point.id())
                    .append(" ref=").append(point.ref() == null ? "?" : point.ref())
                    .append(" marks=").append(point.marks())
                    .append("\n  required: ").append(point.text()).append('\n');
            if (!point.acceptanceCriteria().isEmpty()) {
                sb.append("  acceptance: ").append(String.join("; ", point.acceptanceCriteria()))
                        .append('\n');
            }
        }
    }

    private static void appendGeneralGuidance(StringBuilder sb, MarkScheme scheme) {
        String guidance = scheme == null ? null : scheme.generalGuidance();
        if (guidance != null && !guidance.isBlank()) {
            sb.append("\nSCHEME-LEVEL GENERAL INSTRUCTIONS (board-issued, apply to every\n")
                    .append("decision below, e.g. accept ecf / ignore penalties):\n")
                    .append(guidance.strip()).append('\n');
        }
    }

    private static void appendLearnerAnswer(StringBuilder sb, Answer answer) {
        sb.append("\nLEARNER ANSWER (answer format v2 — Markdown text that may embed\n")
                .append("inline LaTeX math in $…$ / $$…$$ and <sub>/<sup>/<br/> inline HTML;\n")
                .append("read the math and markup literally as the learner's working, never as\n")
                .append("decorative prose; older answers are plain text):\n")
                .append(answer.answerText());
    }

    MarkingCandidate parse(String raw, MarkingContext context, String modelId) {
        JsonNode root = readRoot(raw, "generator output is not valid JSON");
        MarkingCandidate candidate = new MarkingCandidate(modelId,
                parseAllocations(root.get("allocations"), context, raw),
                nodeDouble(root.get("confidence")), raw);
        log.debug("parsed {} candidate allocations from model {}",
                candidate.allocations().size(), modelId);
        return candidate;
    }

    /**
     * v4 batch parse: the response's {@code parts} array must carry exactly one
     * uniquely-numbered object per context — anything else (missing part,
     * duplicated part number, count mismatch) refuses the WHOLE batch so the
     * pipeline can fall back to the per-part topology. Each part's allocations
     * are parsed against THAT part's in-scope points; an allocation referencing
     * another part's point id lands in a candidate it does not belong to and is
     * rejected there by the deterministic bounds validator — never silently
     * reassigned. Every candidate keeps the FULL batch raw output: the audit
     * trail stays self-forensic (the row shows the whole response it came from).
     */
    List<MarkingCandidate> parseBatch(String raw, List<MarkingContext> contexts, String modelId) {
        JsonNode root = readRoot(raw, "batch generator output is not valid JSON");
        JsonNode parts = root.get("parts");
        if (parts == null || !parts.isArray()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    "batch generator output missing parts array", null, raw);
        }
        if (parts.size() != contexts.size()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    "batch generator returned " + parts.size() + " part objects for "
                            + contexts.size() + " answered parts", null, raw);
        }
        Map<Integer, JsonNode> byNumber = new LinkedHashMap<>();
        for (JsonNode part : parts) {
            JsonNode number = part.get("part");
            if (number == null || !number.isNumber()
                    || byNumber.put(number.asInt(), part) != null) {
                throw new CandidateGenerationException(
                        CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                        "batch part object without a unique numeric part number", null, raw);
            }
        }
        List<MarkingCandidate> parsed = new ArrayList<>(contexts.size());
        for (int i = 0; i < contexts.size(); i++) {
            JsonNode part = byNumber.get(i + 1);
            if (part == null) {
                throw new CandidateGenerationException(
                        CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                        "batch generator returned no object for part " + (i + 1), null, raw);
            }
            parsed.add(new MarkingCandidate(modelId,
                    parseAllocations(part.get("allocations"), contexts.get(i), raw),
                    nodeDouble(part.get("confidence")), raw));
        }
        log.debug("parsed {} batch part candidates from model {}", parsed.size(), modelId);
        return parsed;
    }

    private JsonNode readRoot(String raw, String message) {
        String body = extractJsonBody(raw);
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    message, e, raw);
        }
    }

    private List<MarkingCandidate.Allocation> parseAllocations(JsonNode allocations,
                                                               MarkingContext context, String raw) {
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
        return parsed;
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
                "no JSON object in output", null, raw);
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
