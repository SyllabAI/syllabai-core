package com.syllabai.tutor;

import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Grounded generation with the T-026 intervention plan injected into the prompt. */
@Component
public class GroundedTutorGenerator implements TutorGenerator {

    public static final String PROMPT_REGISTRY_KEY = "tutor-grounded";
    public static final String PROMPT_VERSION = "2";

    private static final Logger log = LoggerFactory.getLogger(GroundedTutorGenerator.class);
    private static final int MAX_EVIDENCE_CHARS = 600;
    private static final int MAX_TOTAL_EVIDENCE_CHARS = 4000;

    private final LlmProvider chain;
    private final double temperature;
    private final int maxTokens;

    public GroundedTutorGenerator(LlmProvider chain,
                                  @Value("${syllabai.tutor.temperature:0.2}") double temperature,
                                  @Value("${syllabai.tutor.max-tokens:900}") int maxTokens) {
        this.chain = chain;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    @Override
    public GeneratedAnswer generate(String query, ContextAssembler.TutorContext context) {
        if (!chain.available()) {
            throw new TutorGenerationException(
                    "LLM chain unavailable — set SYLLABAI_GROQ_API_KEY (free tier, ADR-009); "
                            + "grounded answers are impossible without a provider");
        }
        try {
            LlmResponse response = chain.generate(LlmRequest.withOptions(
                    systemPrompt(), userPrompt(query, context), temperature, maxTokens));
            log.info("tutor answer generated via {} ({})", response.providerName(), response.model());
            return new GeneratedAnswer(response.text(), response.model(), response.providerName());
        } catch (LlmProviderException e) {
            throw new TutorGenerationException("LLM chain failed: " + e.getMessage(), e);
        }
    }

    String systemPrompt() {
        return """
                You are SyllabAI's IGCSE/IAL tutor. Answer ONLY from the numbered SOURCES
                provided in the user message, citing them inline as [1], [2], ... exactly
                where their content supports a statement.
                Follow the INTERVENTION PLAN, but do not claim that the learner has a
                diagnosis; the plan is an instructional strategy selected from evidence.
                Rules:
                - If the SOURCES are insufficient to answer safely, say exactly what is
                  missing and stop. Never fill gaps from general knowledge.
                - Never invent spec references, page numbers or topic codes.
                - Do not reveal internal probabilities, model names, diagnostic rules, or
                  private learner-state details to the learner.
                - Be concise: at most 200 words plus citations.
                """;
    }

    String userPrompt(String query, ContextAssembler.TutorContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION:\n").append(query.strip()).append("\n\n");
        sb.append("LEARNER CONTEXT:\n").append(context.learnerBrief()).append("\n\n");
        sb.append("CURRICULUM CONTEXT:\n").append(context.knowledgeBrief()).append("\n\n");
        var plan = context.interventionPlan();
        sb.append("INTERVENTION PLAN:\n").append(plan.type()).append(" — ")
                .append(plan.rationale()).append('\n');
        for (String action : plan.actions()) {
            sb.append("- ").append(action).append('\n');
        }
        sb.append("\nSOURCES (cite these as [n]):\n");
        int rendered = 0;
        for (int i = 0; i < context.evidence().size(); i++) {
            EvidenceItem evidence = context.evidence().get(i);
            String content = bound(evidence.content(), MAX_EVIDENCE_CHARS);
            if (rendered + content.length() > MAX_TOTAL_EVIDENCE_CHARS) {
                log.debug("evidence block truncated at {} items", i);
                break;
            }
            rendered += content.length();
            sb.append("[").append(i + 1).append("] ")
                    .append(sourceLabel(evidence)).append(content.replace('\n', ' ')).append('\n');
        }
        return sb.toString();
    }

    private String sourceLabel(EvidenceItem evidence) {
        String page = evidence.pageStart() == null ? "?" : String.valueOf(evidence.pageStart());
        return switch (evidence.source()) {
            case MARK_SCHEME -> "(mark scheme, p" + page + ") ";
            case QUESTION_PAPER -> "(question paper, p" + page + ") ";
            case SYLLABUS -> "(specification, p" + page + ") ";
            case OTHER -> "(source document, p" + page + ") ";
            case KNOWLEDGE_NODE -> "(spec topic " + evidence.nodeCode() + ") ";
        };
    }

    private static String bound(String text, int max) {
        String safe = text == null ? "" : text.strip();
        return safe.length() <= max ? safe : safe.substring(0, max) + "…";
    }

    static String promptIdentity() {
        return PROMPT_REGISTRY_KEY + "/v" + PROMPT_VERSION;
    }
}
