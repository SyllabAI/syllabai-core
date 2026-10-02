package com.syllabai.learner;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.learner.TrailCursor.Position;
import com.syllabai.learner.dto.FlashcardRatingTrailView;
import com.syllabai.learner.dto.FlashcardRatingTrailView.Event;
import com.syllabai.shared.BadRequestException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bounded raw flashcard-rating trail (T-C61 — GET
 * /api/v1/learners/me/flashcard-rating-trail): the core-side contract that
 * lets the hub TRUE-MERGE cross-device rating trails instead of picking a
 * newest-evidence winner per card (T-C57's recorded limit — with derived
 * schedules on the wire, merging would invent history; with raw events on
 * both sides, the merge is exactly the events that exist, each once).
 *
 * <p>Contract shape:</p>
 * <ul>
 *   <li>BOUNDED: one keyset page per call — {@code limit} defaults to
 *       {@value #DEFAULT_LIMIT} and is CLAMPED to {@value #MAX_LIMIT}
 *       server-side (a client cannot ask the endpoint into an unbounded
 *       read); the client walks pages with the opaque {@code cursor} until
 *       {@code hasMore} is false. Pages are newest first in the total order
 *       {@code (occurred_at DESC, id DESC)} — V47's UUID id cannot order by
 *       itself (random), so the timestamp leads and the id only breaks
 *       occurred_at ties deterministically.</li>
 *   <li>KEYSET, not offset: an append-only trail served newest-first shifts
 *       every offset window when a new row lands mid-walk (duplicates or
 *       gaps); the cursor pins the position of the last served row, so a
 *       concurrent append can only add rows the walk has not reached yet.
 *       The cursor round-trips the EXACT instant (ISO-8601, microsecond
 *       safe) — {@link TrailCursor} is fail-closed: a malformed cursor is a
 *       400, never a guess.</li>
 *   <li>LEARNER-SCOPED by authentication only: the learner clause comes from
 *       the token ({@code @CurrentUserId}), never from the cursor — a
 *       cursor lifted from another account is just a position inside THIS
 *       learner's trail; no state crosses accounts in either direction.</li>
 *   <li>SELF-REPORT, TIMING ONLY (the honesty pin, V47 +
 *       FlashcardRatingFlowIT + the feed edition in
 *       FlashcardReviewScheduleFlowIT): this endpoint serves the same
 *       evidence rows the write side created and touches nothing — no BKT,
 *       no SkillState, no misconception evidence, no review-schedule write;
 *       ADR-031's computed-at-read doctrine extends trivially to raw reads.
 *       The FlowIT pins SkillState staying empty across a full walk.</li>
 *   <li>Attribution is echo-only: {@code subtopicCode} is resolved from the
 *       node the rating row already carries (fail-closed validated at write
 *       time) — this endpoint can never attribute a rating to a node the
 *       curriculum does not have, and an absent node row degrades the CODE
 *       to null (display only), never the row.</li>
 * </ul>
 *
 * <p>Scale (V47's note — one small row per deck-card flip, learner-scoped
 * index {@code ix_fr_learner_recent} fits the walk): a learner's trail is a
 * deck count, not a log firehose; the hub's consumer bounds its own walk
 * client-side and falls back to the derived feed if the trail outgrows the
 * bound (recorded in T-C61's task record).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/flashcard-rating-trail")
public class FlashcardRatingTrailController {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 500;

    private final FlashcardRatingRepository ratings;
    private final KnowledgeNodeRepository knowledgeNodes;

    public FlashcardRatingTrailController(FlashcardRatingRepository ratings,
                                          KnowledgeNodeRepository knowledgeNodes) {
        this.ratings = ratings;
        this.knowledgeNodes = knowledgeNodes;
    }

    @GetMapping
    public FlashcardRatingTrailView trail(@CurrentUserId UUID learnerId,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        // bounded regardless of client input: < 1 is garbage (400), above
        // the cap clamps (a bigger page than the contract is a client bug,
        // not an error the learner should own)
        int pageSize;
        if (limit == null) {
            pageSize = DEFAULT_LIMIT;
        } else if (limit < 1) {
            throw new BadRequestException("limit must be >= 1: " + limit);
        } else {
            pageSize = Math.min(limit, MAX_LIMIT);
        }

        Pageable page = PageRequest.of(0, pageSize + 1); // +1 = hasMore probe
        boolean firstPage = cursor == null || cursor.isBlank();
        List<FlashcardRating> rows;
        if (firstPage) {
            rows = ratings.findByLearnerIdOrderByOccurredAtDescIdDesc(learnerId, page);
        } else {
            Position pos = position(cursor);
            rows = ratings.pageByLearnerNewestFirstAfter(
                    learnerId, pos.occurredAt(), pos.id(), page);
        }

        boolean hasMore = rows.size() > pageSize;
        List<FlashcardRating> served = hasMore ? rows.subList(0, pageSize) : rows;

        // resolved anchor codes for display (batched, honest null on a
        // missing node row — the same posture as the schedule feed)
        Set<UUID> nodeIds = new HashSet<>();
        served.forEach(r -> nodeIds.add(r.nodeId()));
        Map<UUID, String> nodeCodes = nodeIds.isEmpty() ? Map.of()
                : knowledgeNodes.findAllById(nodeIds).stream()
                        .collect(Collectors.toMap(KnowledgeNode::id,
                                KnowledgeNode::code, (a, b) -> a));

        List<Event> events = served.stream()
                .map(r -> Event.from(r, nodeCodes.get(r.nodeId())))
                .toList();

        String nextCursor = null;
        if (hasMore && !served.isEmpty()) {
            FlashcardRating last = served.get(served.size() - 1);
            nextCursor = TrailCursor.encode(last.occurredAt(), last.id());
        }
        return new FlashcardRatingTrailView(events, nextCursor, hasMore, Instant.now());
    }

    /** Decode the client cursor; malformed is a 400 (fail-closed, never a guess). */
    private static Position position(String cursor) {
        try {
            return TrailCursor.decode(cursor);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("malformed cursor: " + e.getMessage());
        }
    }
}
