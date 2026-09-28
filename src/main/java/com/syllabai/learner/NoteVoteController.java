package com.syllabai.learner;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.learner.dto.NoteVoteView;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Note-vote evidence (V48 — the "note votes → core evidence class"
 * contract, ADR-029 tranche 4.9): the hub's "Was this revision note
 * helpful?" footer records the learner's quality signal on their core
 * account, closing the tracked gap the note-footnote surface has carried
 * since the ratings tranche ("core has no votes contract yet").
 *
 * <p>Contract shape: the hub sends the note's subtopic anchor — the same
 * RULE_DERIVED anchor family that drives note placement ("4CH1-S1-a";
 * 112/112 pilot notes resolve to one) — and this controller resolves it
 * against the ingested curriculum knowledge graph with the SAME structural
 * gate as V47 ratings. FAIL-CLOSED ATTRIBUTION: the anchor must resolve to
 * a CURRICULUM-STRUCTURE node below the subject root (UNIT/TOPIC/SUBTOPIC)
 * — the subject root, the semantic layer (CONCEPT/MISCONCEPTION) and
 * unknown codes are all 404 and nothing is written. A vote can never claim
 * a node the curriculum does not have. {@code noteId} is the hub content id
 * ("rn_*") kept as an opaque external reference: the hub owns note identity,
 * core owns the learner model.</p>
 *
 * <p>Evidence class semantics: APPEND-ONLY (every action is a row, the
 * latest row per note is its current vote, and a vote CHANGE is preserved
 * as new evidence rather than overwriting the old) and NEVER mastery (no
 * BKT / SkillState / misconception / review writes — self-report is
 * exposure, history and stats only; pinned by NoteVoteFlowIT). It is also
 * never a content-quality judgement: the content pipeline's VALIDATED
 * states are operator-owned, a learner vote does not touch them.</p>
 *
 * <p>Rate limiting: deliberately NOT in the R8 LLM tier — votes are cheap
 * authenticated writes with zero LLM cost; the auth tier and general
 * authenticated posture cover abuse (same ruling as V47 ratings).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/note-votes")
public class NoteVoteController {

    private final NoteVoteRepository votes;
    private final KnowledgeNodeRepository knowledgeNodes;

    public NoteVoteController(NoteVoteRepository votes,
                              KnowledgeNodeRepository knowledgeNodes) {
        this.votes = votes;
        this.knowledgeNodes = knowledgeNodes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteVoteView record(@CurrentUserId UUID learnerId,
                               @Valid @RequestBody NoteVoteRequest request) {
        NoteVote.Vote vote = NoteVote.Vote.parse(request.vote());
        if (vote == null) {
            throw new BadRequestException(
                    "vote must be \"helpful\" or \"not-helpful\": " + request.vote());
        }
        KnowledgeNode node = knowledgeNodes.findByCode(request.subtopicCode())
                .orElseThrow(() -> new NotFoundException(
                        "unknown note anchor: " + request.subtopicCode()));
        boolean structureNode = node.nodeType() == NodeType.UNIT
                || node.nodeType() == NodeType.TOPIC
                || node.nodeType() == NodeType.SUBTOPIC;
        if (!structureNode) {
            throw new NotFoundException(
                    "not a note anchor (needs a curriculum-structure node below the subject root): "
                            + request.subtopicCode());
        }
        NoteVote saved = votes.save(new NoteVote(
                learnerId, node.id(), request.noteId(), vote, Instant.now()));
        return NoteVoteView.from(saved, request.subtopicCode());
    }

    public record NoteVoteRequest(
            @NotBlank @Size(min = 3, max = 64)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "note id must be the hub content id")
            String noteId,
            @NotBlank String vote,
            @NotBlank @Size(min = 2, max = 64) @Pattern(regexp = "^[A-Za-z0-9-]+$")
            String subtopicCode) {
    }
}
