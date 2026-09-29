package com.syllabai.assessment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * One part-level written answer inside a {@link StructuredSubmitRequest}.
 *
 * @param partId     the {@code question_parts.id} being answered
 * @param answerText the learner's written answer (may be empty = skipped).
 *                   Answer format v2 (HUB-ANSWER-BOX wave 4): Markdown text
 *                   that may embed inline LaTeX math in $…$ / $$…$$ and
 *                   <sub>/<sup>/<br/> inline HTML — the dialect the hub's
 *                   corpus renderer already interprets; v1 answers are plain
 *                   text and remain valid (strictly backward compatible).
 *                   Size-capped (R7): this text is stored AND embedded
 *                   verbatim into Smart Mark LLM prompts — uncapped, a
 *                   ~2 MiB body would be persisted and re-sent to the
 *                   provider on every marking run. 4000 chars matches the
 *                   tutor turn cap.
 */
public record PartAnswerRequest(
        @NotNull UUID partId,
        @Size(max = 4000) String answerText) {
}
