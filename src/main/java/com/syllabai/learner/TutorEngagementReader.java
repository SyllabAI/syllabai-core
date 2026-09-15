package com.syllabai.learner;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter that exposes the V21 engagement signal to the NBA engine through
 * {@code NextBestActionService.TutorEngagementViewReader} — keeps the
 * recommendation module free of the engagement entity while sharing the
 * single source of truth (T-028/T-034 server-composition pattern).
 */
@Service
@Transactional(readOnly = true)
public class TutorEngagementReader
        implements com.syllabai.recommendation.NextBestActionService.TutorEngagementViewReader {

    private final TutorTopicEngagementRepository engagements;

    public TutorEngagementReader(TutorTopicEngagementRepository engagements) {
        this.engagements = engagements;
    }

    @Override
    public Map<UUID, Long> askCountsSince(UUID learnerId, Instant since) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : engagements.countByLearnerSinceGroupedByNode(learnerId, since)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Sprint-2 §9: the per-topic signal-type mix behind the ask counts —
     * what KIND of asks the learner made (doubt, clarification, explanation,
     * misconception-related, prerequisite help), not just how many. Consumers
     * state the mix as evidence; they never re-derive mastery from it.
     */
    @Override
    public Map<UUID, Map<String, Long>> signalCountsSince(UUID learnerId, Instant since) {
        Map<UUID, Map<String, Long>> mix = new HashMap<>();
        for (Object[] row : engagements.countByLearnerSinceGroupedByNodeAndSignal(
                learnerId, since)) {
            mix.computeIfAbsent((UUID) row[0], k -> new HashMap<>())
                    .merge(row[1] == null ? "TOPIC_ENGAGEMENT" : (String) row[1],
                            (Long) row[2], Long::sum);
        }
        return mix;
    }

    /** grouped engagement summary for the learner-state view (asks, last ask, any refusal) */
    public List<com.syllabai.learner.dto.LearnerStateView.TutorEngagementView> groupEngagementSummary(
            List<TutorTopicEngagement> recent, int limit,
            java.util.function.Function<UUID, String> titleResolver) {
        Map<UUID, long[]> grouped = new java.util.LinkedHashMap<>(); // [asks, refusedAny]
        Map<UUID, Instant> lastAsked = new HashMap<>();
        Map<UUID, java.util.Map<String, Long>> signals = new HashMap<>();
        for (TutorTopicEngagement e : recent) {
            long[] agg = grouped.computeIfAbsent(e.nodeId(), k -> new long[2]);
            agg[0]++;
            if (e.refused()) {
                agg[1] = 1;
            }
            lastAsked.merge(e.nodeId(), e.occurredAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
            signals.computeIfAbsent(e.nodeId(), k -> new java.util.LinkedHashMap<>())
                    .merge(e.signalType() == null ? "TOPIC_ENGAGEMENT" : e.signalType(),
                            1L, Long::sum);
        }
        return grouped.entrySet().stream()
                .limit(limit)
                .map(entry -> new com.syllabai.learner.dto.LearnerStateView.TutorEngagementView(
                        entry.getKey(),
                        titleResolver.apply(entry.getKey()),
                        entry.getValue()[0],
                        lastAsked.get(entry.getKey()),
                        entry.getValue()[1] == 1,
                        java.util.Map.copyOf(signals.getOrDefault(entry.getKey(),
                                java.util.Map.of()))))
                .toList();
    }
}
