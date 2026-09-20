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
 * ({@code smart-mark-candidate}, v2 — V25 seed). v2 adds the scheme-level
 * general-guidance section ("accept ecf", "ignore significant-figure penalties"):
 * rendered only when the selected scheme carries {@code generalGuidance} — the
 * scheme-wide instructions the per-point acceptance criteria cannot express.</p>
 */
@Component
public class LlmMarkingCandidateGenerator implements MarkingCandidateGenerator {

    public static final String PROMPT_REGISTRY_KEY = "smart-mark-candidate";
    public static final String PROMPT_VERSION = "2";

    private static final Logger log = LoggerFactory.getLogger(LlmMarkingCandidateGenerator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
                    systemPrompt(), userPrompt(context), 0.1, 800));
        } catch (LlmProviderException e) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.PROVIDER_UNAVAILABLE,
                    "LLM chain failed during marking: " + e.getMessage(), e);
        }

        return parse(response.text(), context, response.model());
    }

    String systemPrompt() {
        return """
                You are an exam marker aligned strictly to the provided mark scheme.
                For every MARK POINT decide exactly one allocation:
                - "awarded": true only when the learner's answer contains the point's
                  required content (spelling variants allowed when the scheme says so);
                - "evidence": the shortest verbatim quote from the learner answer that
                  justifies the decision ("" when not awarded);
                - "rationale": one short sentence.
                Respond with ONLY a JSON object:
                {"confidence": <0..1>, "allocations": [{"markPointId": "<id>",
                "ref": "<ref>", "awarded": true|false, "evidence": "...",
                "rationale": "..."}]}
                Decide EVERY listed mark point. Never invent mark point ids.
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
                    "generator output is not valid JSON", e);
        }
        JsonNode allocations = root.get("allocations");
        if (allocations == null || !allocations.isArray()) {
            throw new CandidateGenerationException(
                    CandidateGenerationException.Reason.UNPARSEABLE_OUTPUT,
                    "generator output missing allocations array", null);
        }
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
                    node.path("awarded").asBoolean(false),
                    textOf(node, "evidence"),
                    textOf(node, "rationale")));
        }
        Double confidence = nodeDouble(root.get("confidence"));
        log.debug("parsed {} candidate allocations from model {}", parsed.size(), modelId);
        return new MarkingCandidate(modelId, List.copyOf(parsed), confidence, raw);
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
