package com.syllabai.learner;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.dto.FlashcardRatingView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.syllabai.shared.BadRequestException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flashcard rating evidence (V47 — the "flashcard ratings → core evidence
 * class" contract, ADR-029 tranche 4.4): the hub's deck player records
 * Still learning / Know actions on the learner's core account.
 *
 * <p>Contract shape: the hub sends the deck's subtopic anchor — the same
 * RULE_DERIVED anchor that drives deck placement ("4CH1-S1-a") — and this
 * controller resolves it against the ingested curriculum knowledge graph.
 * FAIL-CLOSED ATTRIBUTION: an unknown code, or a code that resolves to a
 * non-SUBTOPIC node, is a 404 and nothing is written — a rating can never
 * claim a node the curriculum does not have. {@code cardId} is the hub
 * content id ("fl_*") kept as an opaque external reference: the hub owns
 * card identity, core owns the learner model.</p>
 *
 * <p>Evidence class semantics: APPEND-ONLY (every action is a row, the
 * latest row per card is its current rating, the trail preserves re-rating
 * history for the future review scheduler) and NEVER mastery (no BKT /
 * SkillState / misconception / review writes — self-report is exposure,
 * history and stats only; pinned by FlashcardRatingFlowIT).</p>
 *
 * <p>Rate limiting: deliberately NOT in the R8 LLM tier — ratings are cheap
 * authenticated writes with zero LLM cost (a full 30-card deck flip is 30
 * rows); the auth tier and general authenticated posture cover abuse.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/flashcard-ratings")
public class FlashcardRatingController {

    private final FlashcardRatingRepository ratings;
    private final KnowledgeNodeRepository knowledgeNodes;

    public FlashcardRatingController(FlashcardRatingRepository ratings,
                                     KnowledgeNodeRepository knowledgeNodes) {
        this.ratings = ratings;
        this.knowledgeNodes = knowledgeNodes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlashcardRatingView record(@CurrentUserId UUID learnerId,
                                      @Valid @RequestBody FlashcardRatingRequest request) {
        FlashcardRating.Rating rating = FlashcardRating.Rating.parse(request.rating());
        if (rating == null) {
            throw new BadRequestException(
                    "rating must be \"still-learning\" or \"know\": " + request.rating());
        }
        KnowledgeNode node = knowledgeNodes.findByCode(request.subtopicCode())
                .orElseThrow(() -> new NotFoundException(
                        "unknown subtopic anchor: " + request.subtopicCode()));
        if (node.nodeType() != NodeType.SUBTOPIC) {
            throw new NotFoundException(
                    "not a subtopic anchor: " + request.subtopicCode());
        }
        FlashcardRating saved = ratings.save(new FlashcardRating(
                learnerId, node.id(), request.cardId(), rating, Instant.now()));
        return FlashcardRatingView.from(saved, request.subtopicCode());
    }

    public record FlashcardRatingRequest(
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "card id must be the hub content id")
            String cardId,
            @NotBlank String rating,
            @NotBlank @Size(min = 2, max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$")
            String subtopicCode) {
    }
}
