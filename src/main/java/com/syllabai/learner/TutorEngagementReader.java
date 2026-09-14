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

    /** grouped engagement summary for the learner-state view (asks, last ask, any refusal) */
    public List<com.syllabai.learner.dto.LearnerStateView.TutorEngagementView> groupEngagementSummary(
            List<TutorTopicEngagement> recent, int limit,
            java.util.function.Function<UUID, String> titleResolver) {
        Map<UUID, long[]> grouped = new java.util.LinkedHashMap<>(); // [asks, refusedAny]
        Map<UUID, Instant> lastAsked = new HashMap<>();
        for (TutorTopicEngagement e : recent) {
            long[] agg = grouped.computeIfAbsent(e.nodeId(), k -> new long[2]);
            agg[0]++;
            if (e.refused()) {
                agg[1] = 1;
            }
            lastAsked.merge(e.nodeId(), e.occurredAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return grouped.entrySet().stream()
                .limit(limit)
                .map(entry -> new com.syllabai.learner.dto.LearnerStateView.TutorEngagementView(
                        entry.getKey(),
                        titleResolver.apply(entry.getKey()),
                        entry.getValue()[0],
                        lastAsked.get(entry.getKey()),
                        entry.getValue()[1] == 1))
                .toList();
    }
}
