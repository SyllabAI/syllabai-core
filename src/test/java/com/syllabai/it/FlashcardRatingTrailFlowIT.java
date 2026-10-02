package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.FlashcardRating;
import com.syllabai.learner.FlashcardRatingController;
import com.syllabai.learner.FlashcardRatingController.FlashcardRatingRequest;
import com.syllabai.learner.FlashcardRatingRepository;
import com.syllabai.learner.FlashcardRatingTrailController;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.learner.dto.FlashcardRatingTrailView;
import com.syllabai.shared.BadRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the bounded raw flashcard-rating trail (T-C61 — GET
 * /api/v1/learners/me/flashcard-rating-trail) against a real Postgres. Pins
 * the whole contract end to end:
 *
 * <ul>
 *   <li>the walk is gap-free and duplicate-free across pages in the keyset
 *       order (occurred_at DESC, id DESC), verified against the repository's
 *       full read sorted the same way — the property a client's page
 *       concatenation relies on for a TRUE cross-device merge;</li>
 *   <li>an occurred_at TIE is walked deterministically (id tiebreak) — two
 *       independent walks produce the identical sequence;</li>
 *   <li>the page bound is real: {@code limit} is clamped server-side
 *       ({@value FlashcardRatingTrailController#MAX_LIMIT}), garbage limits
 *       and malformed cursors fail 400 — the endpoint never guesses;</li>
 *   <li>the event view echoes the hub wire vocabulary and the resolved deck
 *       anchor (honest null degrades display, never the row);</li>
 *   <li>learner isolation — another learner's trail never appears;</li>
 *   <li>THE HONESTY PIN, trail edition — a full walk WRITES NOTHING: the
 *       rating row count is untouched and SkillState stays empty (ratings
 *       are self-report TIMING evidence only; ADR-031's computed-at-read
 *       doctrine extends to raw reads).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class FlashcardRatingTrailFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    private static final String NODE_A = "4CH1-S1-a";
    private static final String NODE_B = "4CH1-S1-b";
    private static UUID nodeAId;
    private static UUID nodeBId;

    @Autowired
    private AuthService authService;
    @Autowired
    private FlashcardRatingController ratingsController;
    @Autowired
    private FlashcardRatingTrailController trailController;
    @Autowired
    private FlashcardRatingRepository ratingRows;
    @Autowired
    private KnowledgeNodeRepository knowledgeNodes;
    @Autowired
    private SkillStateRepository skillStates;

    @BeforeAll
    static void seedNodes(@Autowired KnowledgeNodeRepository knowledgeNodes) {
        // mirrors the production 4CH1 graph's level naming (same fixture as
        // FlashcardRatingFlowIT): the deck-anchor level ingests as TOPIC
        KnowledgeNode a = knowledgeNodes.save(new KnowledgeNode(
                NODE_A, NodeType.TOPIC, "States of matter", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        KnowledgeNode b = knowledgeNodes.save(new KnowledgeNode(
                NODE_B, NodeType.TOPIC, "Atoms and molecules", null,
                KnowledgeNode.ValidationStatus.VALIDATED, "it-fixture", "it"));
        nodeAId = a.id();
        nodeBId = b.id();
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-frt-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    /** pace the POSTs like a human deck flip — occurred_at collisions would
     *  make the natural order ambiguous (the tie case is exercised
     *  separately and deterministically via direct rows) */
    private void pace() throws InterruptedException {
        Thread.sleep(60);
    }

    private FlashcardRatingTrailView walk(UUID learner, int limit) {
        List<FlashcardRatingTrailView.Event> events = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        FlashcardRatingTrailView page;
        do {
            page = trailController.trail(learner, limit, cursor);
            events.addAll(page.events());
            cursor = page.nextCursor();
            pages++;
            assertThat(pages).as("walk must terminate within the seeded bound").isLessThan(50);
        } while (page.hasMore());
        assertThat(pages).as("every page except the last is exactly `limit` long")
                .isEqualTo(expectedPages(events.size(), limit));
        return new FlashcardRatingTrailView(events, null, false, page.generatedAt());
    }

    private static int expectedPages(int total, int limit) {
        return total == 0 ? 1 : (total + limit - 1) / limit;
    }

    @Test
    @DisplayName("empty learner: an honest empty page — nothing invented for never-rated cards")
    void emptyTrailForNewLearner() {
        UUID learner = newLearner();
        FlashcardRatingTrailView page = trailController.trail(learner, null, null);
        assertThat(page.events()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.generatedAt()).isNotNull();
    }

    @Test
    @DisplayName("paged walk: newest-first, gap-free, duplicate-free vs the full keyset read; wire vocabulary and anchor echo")
    void pagedWalkIsGapFreeAndDuplicateFree() throws InterruptedException {
        UUID learner = newLearner();
        // 7 events across 2 cards / 2 nodes / both ratings, via the real
        // write path (server-stamped occurred_at, paced)
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w1", "know", NODE_A));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w2", "still-learning", NODE_A));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w1", "know", NODE_A));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w3", "know", NODE_B));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w2", "know", NODE_A));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_w3", "still-learning", NODE_B));
        pace();
        ratingsController.record(learner,
                new FlashcardRatingRequest("fl_w1", "know", NODE_A));

        FlashcardRatingTrailView walked = walk(learner, 3);

        // the walk equals the repository's full read sorted by the SAME keyset
        List<FlashcardRating> expected = ratingRows
                .findByLearnerIdOrderByCardIdAscOccurredAtAscIdAsc(learner).stream()
                .sorted(Comparator.comparing(FlashcardRating::occurredAt).reversed()
                        .thenComparing(Comparator.comparing(FlashcardRating::id).reversed()))
                .toList();
        assertThat(walked.events()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            FlashcardRatingTrailView.Event e = walked.events().get(i);
            assertThat(e.occurredAt()).isEqualTo(expected.get(i).occurredAt());
            assertThat(e.cardId()).isEqualTo(expected.get(i).cardId());
        }
        // newest first: the last write leads the page — compared against
        // the ROUND-TRIPPED read, because Postgres timestamptz ROUNDS to
        // microseconds and the in-memory POST return is not the stored
        // value; the walk serves the stored trail (the cursor path is
        // unaffected either way: TrailCursor encodes the DB-read instant,
        // so both cursor sides carry the stored micros)
        assertThat(walked.events().get(0).cardId()).isEqualTo("fl_w1");
        assertThat(walked.events().get(0).occurredAt())
                .isEqualTo(expected.get(0).occurredAt());
        // wire vocabulary + resolved anchor echo
        assertThat(walked.events().get(0).rating()).isEqualTo("know");
        assertThat(walked.events().get(0).subtopicCode()).isEqualTo(NODE_A);
        assertThat(walked.events().get(0).nodeId()).isEqualTo(nodeAId);
        assertThat(walked.events().stream().filter(e -> e.subtopicCode().equals(NODE_B)).count())
                .isEqualTo(2);
        // no duplicates
        assertThat(walked.events()).allSatisfy(e -> assertThat(
                walked.events().stream().filter(o -> o.cardId().equals(e.cardId())
                        && o.occurredAt().equals(e.occurredAt())).count()).isEqualTo(1));
    }

    @Test
    @DisplayName("occurred_at tie: walked deterministically by the id tiebreak — two walks, one sequence")
    void tieIsDeterministic() {
        UUID learner = newLearner();
        // direct rows with an EXACT occurred_at tie (a production non-event
        // via the paced write path — but the walk must not depend on that)
        Instant tie = Instant.parse("2026-10-02T09:00:00.123456Z");
        ratingRows.save(new FlashcardRating(learner, nodeAId, "fl_t1",
                FlashcardRating.Rating.KNOW, tie.minusSeconds(60)));
        ratingRows.save(new FlashcardRating(learner, nodeAId, "fl_t2",
                FlashcardRating.Rating.KNOW, tie));
        ratingRows.save(new FlashcardRating(learner, nodeAId, "fl_t3",
                FlashcardRating.Rating.STILL_LEARNING, tie));
        ratingRows.save(new FlashcardRating(learner, nodeBId, "fl_t4",
                FlashcardRating.Rating.KNOW, tie.plusSeconds(60)));

        List<String> first = walkIds(learner, 1);
        List<String> second = walkIds(learner, 1);

        // the newest and oldest rows frame the tie; the two TIED rows are
        // adjacent and both present, in SOME order — pinned stable across
        // independent walks (the id tiebreak is a total order, not luck)
        assertThat(first).hasSize(4);
        assertThat(first.get(0)).isEqualTo("fl_t4");
        assertThat(first.get(3)).isEqualTo("fl_t1");
        assertThat(Set.copyOf(first.subList(1, 3))).isEqualTo(Set.of("fl_t2", "fl_t3"));
        assertThat(second).isEqualTo(first); // deterministic across walks
    }

    private List<String> walkIds(UUID learner, int limit) {
        return walk(learner, limit).events().stream().map(e -> e.cardId()).toList();
    }

    @Test
    @DisplayName("bounded contract: limit clamped server-side; garbage limit and malformed cursor fail 400")
    void boundedContract() {
        UUID learner = newLearner();
        ratingRows.save(new FlashcardRating(learner, nodeAId, "fl_b1",
                FlashcardRating.Rating.KNOW, Instant.now()));

        // clamp: an oversized page request still serves, capped
        FlashcardRatingTrailView page = trailController.trail(learner, 100_000, null);
        assertThat(page.events()).hasSize(1);
        assertThat(page.hasMore()).isFalse();

        assertThatThrownBy(() -> trailController.trail(learner, 0, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> trailController.trail(learner, -3, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> trailController.trail(learner, 10, "garbage-cursor"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("learner isolation: another learner's trail never appears")
    void learnerIsolation() throws InterruptedException {
        UUID a = newLearner();
        UUID b = newLearner();
        ratingsController.record(a, new FlashcardRatingRequest("fl_iso_a", "know", NODE_A));
        pace();
        ratingsController.record(b, new FlashcardRatingRequest("fl_iso_b", "know", NODE_A));

        assertThat(walkIds(a, 10)).containsExactly("fl_iso_a");
        assertThat(walkIds(b, 10)).containsExactly("fl_iso_b");
    }

    @Test
    @DisplayName("THE HONESTY PIN, trail edition: a full walk writes nothing — row count untouched, SkillState stays empty")
    void trailWalkWritesNothing() throws InterruptedException {
        UUID learner = newLearner();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_pin1", "know", NODE_A));
        pace();
        ratingsController.record(learner, new FlashcardRatingRequest("fl_pin2", "still-learning", NODE_B));

        long rowsBefore = ratingRows.count();
        long skillRowsBefore = skillStates.count();

        walk(learner, 1); // the smallest page size — forces the full walk

        assertThat(ratingRows.count()).isEqualTo(rowsBefore);
        assertThat(skillStates.count()).isEqualTo(skillRowsBefore).isZero();
    }
}
