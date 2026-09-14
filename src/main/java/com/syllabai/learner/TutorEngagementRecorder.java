package com.syllabai.learner;

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
 * Learner-memory half of the Tutor pipeline (V21, P7): turns every
 * {@code TutorAnsweredEvent} into structured per-topic engagement rows.
 *
 * <p>Hard boundaries (operator directive, P7): only the deterministic intent
 * matcher's topic IDs, the grounding strength and the model identity are
 * recorded — never the raw chat text, never LLM-invented entities. Anonymous
 * previews (null learnerId) write nothing. The research log
 * ({@code TelemetryService}) keeps the full exchange independently.</p>
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
        String signal = classify(event.question(), event.interventionType());
        List<TutorTopicEngagement> rows = new ArrayList<>(event.matchedTopicIds().size());
        for (UUID nodeId : event.matchedTopicIds()) {
            rows.add(new TutorTopicEngagement(
                    event.learnerId(), nodeId, event.occurredAt(),
                    event.evidenceCount(), event.refused(), event.answerModel(), signal));
        }
        engagements.saveAll(rows);
        log.debug("recorded {} tutor engagement row(s) for learner {} (refused={}, signal={}, model={})",
                rows.size(), event.learnerId(), event.refused(), signal, event.answerModel());
    }

    /**
     * V23 deterministic signal classification, one per row, precedence-ordered.
     * Sources (all deterministic, none LLM-derived):
     * <ol>
     *   <li>MISCONCEPTION_RELATED — the tutor policy intervened on an active
     *       BDT misconception on a matched topic (the intervention plan is a
     *       rule output over measured learner state);</li>
     *   <li>DOUBT_SIGNAL — the question contains an explicit confusion phrase
     *       (the learner's own words, classified by fixed substring list);</li>
     *   <li>EXPLANATION_REQUEST — explanation command words (the same command
     *       vocabulary the intent matcher's stop list defines);</li>
     *   <li>TOPIC_ENGAGEMENT — the topic-match fact alone (default).</li>
     * </ol>
     * The raw question is read here and discarded: only the TYPE is recorded.
     */
    static String classify(String question, String interventionType) {
        if ("MISCONCEPTION_REMEDIATION".equals(interventionType)) {
            return "MISCONCEPTION_RELATED";
        }
        String q = question == null ? "" : question.toLowerCase(java.util.Locale.ROOT);
        for (String pattern : DOUBT_PATTERNS) {
            if (q.contains(pattern)) {
                return "DOUBT_SIGNAL";
            }
        }
        for (String pattern : EXPLANATION_PATTERNS) {
            if (q.contains(pattern)) {
                return "EXPLANATION_REQUEST";
            }
        }
        return "TOPIC_ENGAGEMENT";
    }

    /** explicit self-reported confusion (fixed list, substring, case-insensitive) */
    private static final List<String> DOUBT_PATTERNS = List.of(
            "don't understand", "dont understand", "do not understand",
            "confused", "not sure", "don't get", "dont get", "don't see", "dont see",
            "struggling", "stuck on", "no idea", "lost on");

    /** explanation command vocabulary (mirrors the intent matcher's) */
    private static final List<String> EXPLANATION_PATTERNS = List.of(
            "explain", "why ", "why?", "how ", "how?", "what is", "what are",
            "what does", "what do", "describe", "help me understand");
}
