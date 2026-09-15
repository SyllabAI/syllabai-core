package com.syllabai.learner;

import com.syllabai.shared.events.ClaInteractionEvent;
import com.syllabai.shared.events.TutorAnsweredEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner-memory half of the conversational pipeline (V21, P7): turns every
 * {@code TutorAnsweredEvent} (free Tutor) and every {@code ClaInteractionEvent}
 * (Contextual Learning Assistant, V24) into structured per-topic engagement
 * rows.
 *
 * <p>Hard boundaries (operator directive, P7; LIM contract §3; CLA contract
 * §6): only the deterministic topic anchors, the grounding strength and the
 * model identity are recorded — never the raw chat text, never LLM-invented
 * entities. Anonymous previews (null learnerId) write nothing. The research
 * log ({@code TelemetryService}) keeps the full exchange independently.</p>
 *
 * <p>Order 30: after the learner-model updates (10) and ahead of nothing that
 * depends on it — engagement rows are pure appends with no read-modify-write,
 * so ordering is documentation, not coordination.</p>
 */
@Service
public class TutorEngagementRecorder {

    private static final Logger log = LoggerFactory.getLogger(TutorEngagementRecorder.class);

    private final TutorTopicEngagementRepository engagements;

    public TutorEngagementRecorder(TutorTopicEngagementRepository engagements) {
        this.engagements = engagements;
    }

    @EventListener
    @Order(30)
    @Transactional
    public void onTutorAnswered(TutorAnsweredEvent event) {
        if (event.learnerId() == null || event.matchedTopicIds().isEmpty()) {
            return; // anonymous preview, or nothing matched deterministically
        }
        append(event.learnerId(), event.matchedTopicIds(), event.occurredAt(),
                event.evidenceCount(), event.refused(), event.answerModel(),
                classify(event.question(), event.interventionType()),
                "FREE_TUTOR", null, null, null);
    }

    /**
     * V24: the Contextual Learning Assistant attaches to LIM via the extension
     * rules (LIM §4): deterministic anchors are the SERVER-RESOLVED context
     * (never model-invented), classification reuses the same precedence list,
     * and rows carry the surface identity triple (surface, mode, context).
     */
    @EventListener
    @Order(30)
    @Transactional
    public void onClaInteraction(ClaInteractionEvent event) {
        if (event.learnerId() == null || event.matchedTopicIds().isEmpty()) {
            return; // defensive symmetry with the tutor path — the CLA surface is authenticated
        }
        append(event.learnerId(), event.matchedTopicIds(), event.occurredAt(),
                event.evidenceCount(), event.refused(), event.answerModel(),
                classify(event.question(), event.interventionType()),
                "CONTEXTUAL_ASSISTANT", event.responseMode(), event.contextKind(),
                event.contextReference());
    }

    private void append(UUID learnerId, List<UUID> nodeIds, java.time.Instant occurredAt,
                        int evidenceCount, boolean refused, String answerModel, String signal,
                        String surface, String responseMode, String contextKind,
                        UUID contextReference) {
        List<TutorTopicEngagement> rows = new ArrayList<>(nodeIds.size());
        for (UUID nodeId : nodeIds) {
            rows.add(new TutorTopicEngagement(learnerId, nodeId, occurredAt,
                    evidenceCount, refused, answerModel, signal,
                    surface, responseMode, contextKind, contextReference,
                    SIGNAL_POLICY_VERSION));
        }
        engagements.saveAll(rows);
        log.debug("recorded {} {} engagement row(s) for learner {} (refused={}, signal={}, model={}, policy={})",
                rows.size(), surface, learnerId, refused, signal, answerModel,
                SIGNAL_POLICY_VERSION);
    }

    /**
     * V25 deterministic signal classification (sprint-2 §9), one per row,
     * precedence-ordered. Sources (all deterministic, none LLM-derived):
     * <ol>
     *   <li>MISCONCEPTION_RELATED — the tutor policy intervened on an active
     *       BDT misconception on a matched topic (the intervention plan is a
     *       rule output over measured learner state);</li>
     *   <li>PREREQUISITE_HELP — the tutor policy's deterministic plan chose
     *       PREREQUISITE_REVIEW for the ask (measured struggle/BDT state says
     *       the learner needs the foundation first);</li>
     *   <li>DOUBT_SIGNAL — the question contains an explicit confusion phrase
     *       (the learner's own words, classified by fixed substring list);</li>
     *   <li>CLARIFICATION_REQUEST — the question asks to re-state or clarify
     *       a previous explanation (fixed phrase list). Checked before the
     *       explanation patterns so "what do you mean" is a clarification,
     *       not a fresh explanation request;</li>
     *   <li>EXPLANATION_REQUEST — explanation command words (the same command
     *       vocabulary the intent matcher's stop list defines);</li>
     *   <li>TOPIC_ENGAGEMENT — the topic-match fact alone (default).</li>
     * </ol>
     * The raw question is read here and discarded: only the TYPE is recorded.
     * Signals that would need multi-row context (repeated explanation
     * request, post-explanation engagement, unresolved question) are NOT
     * row types — consumers derive them over the windowed rows; the refused
     * flag stays the honest "unresolved interaction" fact.
     */
    static String classify(String question, String interventionType) {
        if ("MISCONCEPTION_REMEDIATION".equals(interventionType)) {
            return "MISCONCEPTION_RELATED";
        }
        if ("PREREQUISITE_REVIEW".equals(interventionType)) {
            return "PREREQUISITE_HELP";
        }
        String q = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        for (String pattern : DOUBT_PATTERNS) {
            if (q.contains(pattern)) {
                return "DOUBT_SIGNAL";
            }
        }
        for (String pattern : CLARIFICATION_PATTERNS) {
            if (q.contains(pattern)) {
                return "CLARIFICATION_REQUEST";
            }
        }
        for (String pattern : EXPLANATION_PATTERNS) {
            if (q.contains(pattern)) {
                return "EXPLANATION_REQUEST";
            }
        }
        return "TOPIC_ENGAGEMENT";
    }

    /** the deterministic classifier version stamped on every row (V25 provenance) */
    static final String SIGNAL_POLICY_VERSION = "tutor-signals/v2";

    /** explicit self-reported confusion (fixed list, substring, case-insensitive) */
    private static final List<String> DOUBT_PATTERNS = List.of(
            "don't understand", "dont understand", "do not understand",
            "confused", "not sure", "don't get", "dont get", "don't see", "dont see",
            "struggling", "stuck on", "no idea", "lost on");

    /** requests to re-state / clarify a previous explanation (V25, §9) */
    private static final List<String> CLARIFICATION_PATTERNS = List.of(
            "clarify", "clarification", "what do you mean", "what did you mean",
            "you mean", "say that again", "say it again", "repeat that",
            "didn't catch that", "did not catch that", "rephrase", "more precisely",
            "be more specific");

    /** explanation command vocabulary (mirrors the intent matcher's) */
    private static final List<String> EXPLANATION_PATTERNS = List.of(
            "explain", "why ", "why?", "how ", "how?", "what is", "what are",
            "what does", "what do", "describe", "help me understand");
}
