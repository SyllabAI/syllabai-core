package com.syllabai.tutor;

import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Grounded generation with the T-026 intervention plan injected into the prompt. */
@Component
public class GroundedTutorGenerator implements TutorGenerator {

    public static final String PROMPT_REGISTRY_KEY = "tutor-grounded";
    public static final String PROMPT_VERSION = "5";

    private static final Logger log = LoggerFactory.getLogger(GroundedTutorGenerator.class);
    private static final int MAX_EVIDENCE_CHARS = 600;
    private static final int MAX_TOTAL_EVIDENCE_CHARS = 4000;

    /** Working-memory budgets (s139): one prior turn renders at most this
     *  long in the prompt (tutor answers are ~200 words, so an untruncated
     *  exchange fits; older turns lose their tail first — newest-last
     *  retention keeps the turns the learner is most likely referring to). */
    private static final int MAX_CONVERSATION_TURN_CHARS = 800;
    private static final int MAX_TOTAL_CONVERSATION_CHARS = 2400;

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
        return generate(query, List.of(), context);
    }

    @Override
    public GeneratedAnswer generate(String query, List<ConversationTurn> history,
                                    ContextAssembler.TutorContext context) {
        if (!chain.available()) {
            throw new TutorGenerationException(
                    "LLM chain unavailable — set SYLLABAI_GROQ_API_KEY (free tier, ADR-009); "
                            + "grounded answers are impossible without a provider");
        }
        try {
            LlmResponse response = chain.generate(LlmRequest.withOptions(
                    systemPrompt(), userPrompt(query, history, context), temperature, maxTokens));
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
                - Formatting: GitHub-flavored markdown — short paragraphs, bold key terms,
                  bullet lists where they aid scanning; no heading lines.
                - Every chemical species, ion, formula and equation is LaTeX with mhchem,
                  inline in dollar signs: $\\ce{H2O}$, $\\ce{Cu^2+}$,
                  $\\ce{2H2 + O2 -> 2H2O}$; other mathematics as $...$ or $$...$$.
                  Convert sub/superscripts, arrows and state symbols from the SOURCES
                  into this notation. Never use HTML tags or Unicode sub/superscripts.
                - A CONVERSATION SO FAR block, when present, is this learner's
                  earlier chat in the same session. Answer the final QUESTION;
                  use earlier turns only to resolve references ("it", "the second
                  point", "that equation"). Earlier tutor messages are not sources:
                  cite ONLY the SOURCES numbered in this message, and
                  do not repeat an earlier answer verbatim — build on it.
                - A RECENT LEARNING EXPERIENCES block, when present, summarizes
                  this learner's earlier work on the current topics across
                  sessions: prior tutor asks, practice outcomes and spaced-review
                  status. Use it to open the FIRST answer of a session with one
                  brief sentence of continuity when it genuinely helps (e.g.
                  picking up where they left off, or acknowledging a topic they
                  have been practising) — never as diagnosis, never quoting
                  numbers, probabilities or internal state. It is context about
                  the learner, NOT evidence about the subject: every subject
                  claim still needs a SOURCES citation.
                """;
    }

    String userPrompt(String query, List<ConversationTurn> history,
                      ContextAssembler.TutorContext context) {
        StringBuilder sb = new StringBuilder();
        appendConversation(sb, history);
        sb.append("QUESTION:\n").append(query.strip()).append("\n\n");
        sb.append("LEARNER CONTEXT:\n").append(context.learnerBrief()).append("\n\n");
        // s140 episodic memory: the cross-session digest rides between the
        // learner's state and the curriculum — omitted entirely when the
        // learner has no history on the matched topics (or on the CLA surface,
        // which composes memory-free contexts).
        if (context.memoryBrief() != null && !context.memoryBrief().isBlank()) {
            sb.append("RECENT LEARNING EXPERIENCES (this learner's earlier work on "
                            + "the current topics, across sessions):\n")
                    .append(context.memoryBrief()).append("\n\n");
        }
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

    /**
     * CONVERSATION SO FAR block, oldest first (the model reads the chat in
     * order and the final QUESTION right after it). Empty history ⇒ the block
     * is omitted entirely, so anchored single-turn callers (CLA) get the v3
     * prompt shape unchanged apart from the v4 system rule.
     *
     * <p>Budget: newest turns are kept whole and the OLDEST are dropped once
     * {@link #MAX_TOTAL_CONVERSATION_CHARS} is exhausted — a follow-up refers
     * to the immediately preceding exchange far more often than to the first.
     * History arrives pre-sanitized ({@link ConversationTurn#sanitize}); this
     * method only bounds what reaches the prompt.</p>
     */
    private static void appendConversation(StringBuilder sb, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        // select newest-first until the budget is spent, then render oldest-first
        List<String> kept = new ArrayList<>(history.size());
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationTurn turn = history.get(i);
            String bounded = bound(turn.text(), MAX_CONVERSATION_TURN_CHARS);
            if (used + bounded.length() > MAX_TOTAL_CONVERSATION_CHARS && !kept.isEmpty()) {
                break;
            }
            kept.add(bounded);
            used += bounded.length();
            if (used >= MAX_TOTAL_CONVERSATION_CHARS) {
                break;
            }
        }
        if (kept.isEmpty()) {
            return;
        }
        sb.append("CONVERSATION SO FAR (earlier turns, citation markers removed):\n");
        for (int i = kept.size() - 1; i >= 0; i--) {
            ConversationTurn turn = history.get(history.size() - 1 - i);
            sb.append(turn.isAssistant() ? "TUTOR: " : "LEARNER: ").append(kept.get(i)).append('\n');
        }
        sb.append('\n');
    }

    private String sourceLabel(EvidenceItem evidence) {
        String page = evidence.pageStart() == null ? "?" : String.valueOf(evidence.pageStart());
        return switch (evidence.source()) {
            case MARK_SCHEME -> "(mark scheme, p" + page + ") ";
            case QUESTION_PAPER -> "(question paper, p" + page + ") ";
            case SYLLABUS -> "(specification, p" + page + ") ";
            case OTHER -> "(source document, p" + page + ") ";
            case KNOWLEDGE_NODE -> "(spec topic " + evidence.nodeCode() + ") ";
            case LEARNER_WORK -> "(the learner's submitted work) ";
            case NOTE -> "(revision notes, p" + page + ") ";
            case TEXTBOOK -> "(textbook, p" + page + ") ";
            case CARD -> "(question card) ";
        };
    }

    private static String bound(String text, int max) {
        String safe = text == null ? "" : text.strip();
        return safe.length() <= max ? safe : safe.substring(0, max) + "…";
    }

    /** registered prompt identity, e.g. "tutor-grounded/v3" — public since V24:
     * the CLA pipeline reuses this prompt verbatim and must record the SAME
     * identity (contract: compose the Tutor's generation stack; a duplicated
     * constant would create drift risk). */
    public static String promptIdentity() {
        return PROMPT_REGISTRY_KEY + "/v" + PROMPT_VERSION;
    }
}
