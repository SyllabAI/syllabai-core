package com.syllabai.tutor;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.tutor.dto.TutorAnswerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tutor ask endpoint (T-024 backend surface — the chat UI is T-025).
 * Authenticated: students ask, teachers preview with their own identity.
 *
 * <p>s139 working memory: the client sends the chat's prior turns with each
 * ask ({@code history}, newest-last). The history is the learner's own
 * visible transcript — held client-side, passed per-ask, never persisted
 * here (the Spec §22 session endpoints remain the only sanctioned
 * server-side transcript surface). Structural violations (unknown role,
 * oversized turns, too many turns) fail validation with a 400; the service
 * layer additionally sanitizes whatever passes, so a well-formed-but-odd
 * history degrades gracefully instead of failing the ask.</p>
 */
@RestController
@RequestMapping("/api/v1/tutor")
public class TutorController {

    private final KaRagService kaRag;

    public TutorController(KaRagService kaRag) {
        this.kaRag = kaRag;
    }

    /**
     * @param question the turn to answer (unchanged contract)
     * @param history  optional prior turns of the same chat, oldest first;
     *                 null/absent = single-turn ask (pre-s139 clients)
     */
    public record TutorAskRequest(
            @NotBlank @Size(max = 2000) String question,
            @Size(max = ConversationTurn.MAX_HISTORY_TURNS) List<@Valid HistoryTurn> history) {

        /** One client-held transcript turn. */
        public record HistoryTurn(
                @NotBlank @Pattern(regexp = ConversationTurn.ROLE_USER + "|"
                        + ConversationTurn.ROLE_ASSISTANT) String role,
                @NotBlank @Size(max = ConversationTurn.MAX_TURN_CHARS) String text) {
        }
    }

    @PostMapping("/ask")
    public TutorAnswerView ask(@CurrentUserId UUID learnerId,
                               @Valid @RequestBody TutorAskRequest request) {
        List<ConversationTurn> history = new ArrayList<>();
        if (request.history() != null) {
            for (TutorAskRequest.HistoryTurn turn : request.history()) {
                ConversationTurn clean = ConversationTurn.of(turn.role(), turn.text());
                if (clean != null) {
                    history.add(clean);
                }
            }
        }
        return kaRag.ask(learnerId, request.question(), history);
    }
}
