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
        List<TutorTopicEngagement> rows = new ArrayList<>(event.matchedTopicIds().size());
        for (UUID nodeId : event.matchedTopicIds()) {
            rows.add(new TutorTopicEngagement(
                    event.learnerId(), nodeId, event.occurredAt(),
                    event.evidenceCount(), event.refused(), event.answerModel()));
        }
        engagements.saveAll(rows);
        log.debug("recorded {} tutor engagement row(s) for learner {} (refused={}, model={})",
                rows.size(), event.learnerId(), event.refused(), event.answerModel());
    }
}
