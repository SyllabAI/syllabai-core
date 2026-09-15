package com.syllabai.cla;

import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.identity.CurrentUserId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contextual Learning Assistant surface — step 1 (CLA contract §10.1).
 * Learner-scoped, authenticated: the learner says WHAT THEY ARE LOOKING AT
 * (opaque references: subject root + topic node) and WHAT KIND OF HELP they
 * want (explicit mode); the server resolves everything else.
 *
 * <p>Route security: authenticated (learner surface under /api/v1/learners/me
 * — the SecurityConfig default rule). Students ask on their own identity; a
 * teacher token previews with its own identity, exactly like the free Tutor
 * — there is no cross-learner context composition anywhere on this
 * surface.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/cla")
public class ClaController {

    private final ClaService cla;

    public ClaController(ClaService cla) {
        this.cla = cla;
    }

    /**
     * @param rootId      opaque reference: the subject KG root the learner is
     *                    working in (server-resolves subject + curriculum version)
     * @param topicNodeId opaque reference: the anchored curriculum topic
     *                    (server-resolves, VALIDATED-only, subject-isolated)
     * @param mode        explicit ResponseMode (EXPLAIN | SUMMARIZE in step 1;
     *                    anything else is rejected — unknown values fail 400)
     * @param question    the learner's question within the anchored context
     */
    public record ClaAskRequest(
            @NotNull UUID rootId,
            @NotNull UUID topicNodeId,
            @NotNull ResponseMode mode,
            @NotBlank @Size(max = 2000) String question) {
    }

    @PostMapping("/ask")
    public ClaAnswerView ask(@CurrentUserId UUID learnerId,
                             @Valid @RequestBody ClaAskRequest request) {
        return cla.contextualAsk(learnerId, request.rootId(), request.topicNodeId(),
                request.mode(), request.question());
    }
}
