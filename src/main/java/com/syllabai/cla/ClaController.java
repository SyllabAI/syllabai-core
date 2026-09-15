package com.syllabai.cla;

import com.syllabai.cla.dto.ClaAnswerView;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.BadRequestException;
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
 * Contextual Learning Assistant surface — steps 1–2 (CLA contract §10.1,
 * §10.2). Learner-scoped, authenticated: the learner says WHAT THEY ARE
 * LOOKING AT (an explicit context kind + opaque references) and WHAT KIND OF
 * HELP they want (explicit mode); the server resolves everything else.
 *
 * <p>Route security: authenticated (learner surface under /api/v1/learners/me
 * — the SecurityConfig default rule). Students ask on their own identity; a
 * teacher token previews with its own identity, exactly like the free Tutor
 * — there is no cross-learner context composition anywhere on this
 * surface.</p>
 *
 * <p>Fail-closed request semantics: an unknown kind/mode is a 400 (undeclared
 * values rejected, §3); a kind whose required reference is missing is a 400;
 * kinds not served by the current runtime step are a 400 (closed enum, §1);
 * unresolvable references are 404 with no existence oracles; CHECK without
 * attempt evidence is a 409 (the §7.3 answer-leakage gate).</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/cla")
public class ClaController {

    private final ClaService cla;

    public ClaController(ClaService cla) {
        this.cla = cla;
    }

    /**
     * @param kind        closed context kind (§1). Runtime serves KG_TOPIC and
     *                    PAST_PAPER_QUESTION in steps 1–2.
     * @param rootId      KG_TOPIC: the subject KG root (server-resolves subject
     *                    + curriculum version)
     * @param topicNodeId KG_TOPIC: the anchored curriculum topic (server-resolves,
     *                    VALIDATED-only, subject-isolated)
     * @param questionId  PAST_PAPER_QUESTION: the anchored question (server-
     *                    resolves through the full serving gate; attempt state
     *                    read server-side for the §7 gate)
     * @param specCode    SPECIFICATION_POINT: the spec-point code the learner is
     *                    reading (e.g. "4CH1-1.18") — resolved server-side,
     *                    subject-isolated, VALIDATED-only
     * @param mode        explicit ResponseMode (EXPLAIN | SUMMARIZE | HINT |
     *                    CHECK); unknown values fail 400
     * @param question    the learner's question within the anchored context
     */
    public record ClaAskRequest(
            @NotNull ResourceContext.Kind kind,
            UUID rootId,
            UUID topicNodeId,
            UUID questionId,
            @Size(max = 80) String specCode,
            @NotNull ResponseMode mode,
            @NotBlank @Size(max = 2000) String question) {
    }

    @PostMapping("/ask")
    public ClaAnswerView ask(@CurrentUserId UUID learnerId,
                             @Valid @RequestBody ClaAskRequest request) {
        if (request.kind() == null) {
            throw new BadRequestException("context kind is required");
        }
        return cla.contextualAsk(learnerId, request.kind(), request.rootId(),
                request.topicNodeId(), request.questionId(), request.specCode(),
                request.mode(), request.question());
    }
}
