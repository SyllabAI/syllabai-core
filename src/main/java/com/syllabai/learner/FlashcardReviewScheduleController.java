package com.syllabai.learner;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.learner.dto.FlashcardReviewScheduleView;
import com.syllabai.learner.dto.FlashcardReviewScheduleView.CardScheduleView;
import com.syllabai.learner.dto.FlashcardReviewScheduleView.Summary;
import com.syllabai.learner.flashcard.FlashcardReviewParams;
import com.syllabai.learner.flashcard.FlashcardReviewScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flashcard review-schedule feed (T-C53 — the V47 rating trail's reserved
 * consumer): GET /api/v1/learners/me/flashcard-review-schedule.
 *
 * <p>The hub's deck queues have always been device-local (tranche 4.8 derives
 * them from the browser-local overlay — "core serves the latest 50 events for
 * the learner model, not a scheduling feed"). This endpoint is the core-side
 * half of that contract: the SAME Ebbinghaus ladder, derived at READ from the
 * learner's account-level append-only trail, so any core-backed surface (My
 * State, next-best-actions, a future hub consumer) sees the account's true
 * queue rather than one device's.</p>
 *
 * <p>Doctrine pins (all inherited, all test-verified):</p>
 * <ul>
 *   <li>COMPUTED AT READ, NEVER PERSISTED (ADR-031): no schedule table, no
 *       {@code review_schedules} row — that table stays the marked-attempt
 *       spec-point queue. This read model writes nothing.</li>
 *   <li>THE HONESTY PIN (V47, FlashcardRatingFlowIT): ratings are
 *       self-report — this feed drives review TIMING only and never touches
 *       BKT/SkillState/misconception state. A due card says nothing about
 *       mastery.</li>
 *   <li>Ladder parity with the hub's shipped {@code lib/flashcard-review.ts}
 *       (1·2·4·8·16, capped 32) via {@code syllabai.learner.flashcard-review}
 *       — configurable, registry-seeded (V59 {@code learner.flashcard-review}),
 *       never hard-coded.</li>
 *   <li>Attribution is the resolved deck anchor from the trail itself — this
 *       endpoint can never invent an anchor the rating flow did not already
 *       fail-closed validate (unknown codes are 404 at write time).</li>
 * </ul>
 *
 * <p>Scale: one small row per deck-card flip (V47's note), full trail read
 * per learner, per-card grouping in memory — a learner's trail is a deck
 * count, not a log firehose.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/flashcard-review-schedule")
public class FlashcardReviewScheduleController {

    private final FlashcardRatingRepository ratings;
    private final KnowledgeNodeRepository knowledgeNodes;
    private final LearnerProperties properties;

    /** pure and stateless — the BktEngine pattern without a config bean */
    private final FlashcardReviewScheduler scheduler = new FlashcardReviewScheduler();

    public FlashcardReviewScheduleController(FlashcardRatingRepository ratings,
                                             KnowledgeNodeRepository knowledgeNodes,
                                             LearnerProperties properties) {
        this.ratings = ratings;
        this.knowledgeNodes = knowledgeNodes;
        this.properties = properties;
    }

    @GetMapping
    public FlashcardReviewScheduleView schedule(@CurrentUserId UUID learnerId) {
        Instant now = Instant.now();
        FlashcardReviewParams params = properties.flashcardReview().toParams();

        // full trail, card-grouped, chronological within the card (the
        // repository's ORDER BY card_id, occurred_at, id read)
        List<FlashcardRating> trail =
                ratings.findByLearnerIdOrderByCardIdAscOccurredAtAscIdAsc(learnerId);
        Map<String, List<FlashcardRating>> byCard = new LinkedHashMap<>();
        for (FlashcardRating r : trail) {
            byCard.computeIfAbsent(r.cardId(), k -> new ArrayList<>()).add(r);
        }

        // dueAt order = due cards first, stalest due date first — the order a
        // learner should work them (cardId tiebreak keeps the feed
        // deterministic for identical due dates)
        List<FlashcardReviewScheduler.CardSchedule> schedules = byCard.entrySet().stream()
                .map(e -> scheduler.scheduleCard(e.getKey(), e.getValue(), now, params))
                .sorted(Comparator.comparing(FlashcardReviewScheduler.CardSchedule::dueAt)
                        .thenComparing(FlashcardReviewScheduler.CardSchedule::cardId))
                .toList();

        // resolved anchor codes for display (batched, honest-null on a missing
        // node row — same posture as the learner-state title resolution)
        Set<UUID> nodeIds = new HashSet<>();
        schedules.forEach(s -> nodeIds.add(s.nodeId()));
        Map<UUID, String> nodeCodes = nodeIds.isEmpty() ? Map.of()
                : knowledgeNodes.findAllById(nodeIds).stream()
                        .collect(Collectors.toMap(KnowledgeNode::id,
                                KnowledgeNode::code, (a, b) -> a));

        List<CardScheduleView> cards = schedules.stream()
                .map(s -> CardScheduleView.from(s, nodeCodes))
                .toList();

        long due = schedules.stream().filter(FlashcardReviewScheduler.CardSchedule::due).count();
        Instant nextDueAt = schedules.stream()
                .filter(s -> !s.due())
                .map(FlashcardReviewScheduler.CardSchedule::dueAt)
                .min(Instant::compareTo)
                .orElse(null);

        return new FlashcardReviewScheduleView(learnerId, now,
                new Summary(due, schedules.size() - due, nextDueAt), cards);
    }
}
