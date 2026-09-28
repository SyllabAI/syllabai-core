package com.syllabai.answerinput;

import com.syllabai.identity.CurrentUserId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner answer-input transcription surface (HUB-ANSWER-BOX wave 3). One entry
 * point: hand the hub an image (drawing-pad PNG or a photo), get back the plain
 * text the learner's handwriting contains, ready to insert at the caret of the
 * shared AnswerTextarea. Authenticated learners only (SecurityConfig default);
 * the LLM-tier rate limit covers this path (RateLimitFilter).
 *
 * <p>NOT a persistence surface: nothing here writes rows or evidence — the image
 * is never stored, and the returned text becomes part of the answer only when the
 * learner's own next autosave/submit carries it. Never exposes JPA entities.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/answer-input")
public class TranscriptionController {

    private final AnswerInputTranscriptionService service;

    public TranscriptionController(AnswerInputTranscriptionService service) {
        this.service = service;
    }

    /**
     * Transcribes one image to plain text. Request body is JSON (base64 payload,
     * not multipart) — the image is already client-side downscaled by the hub, so
     * the whole exchange stays a single small request.
     *
     * @return 200 with the transcribed text and provider provenance
     * @throws AnswerInputTranscriptionService.BadRequestException 400 (bad mime /
     *         bad base64 / empty payload)
     * @throws AnswerInputTranscriptionService.ImageTooLargeException 413 (over cap)
     * @throws AnswerInputTranscriptionService.NothingReadableException 422 (no
     *         handwriting found)
     * @throws AnswerInputTranscriptionService.TranscriptionUnavailableException 503
     *         (no vision-capable provider available)
     */
    @PostMapping(value = "/transcribe", consumes = "application/json")
    @ResponseStatus(HttpStatus.OK)
    public TranscriptionView transcribe(@CurrentUserId UUID learnerId,
                                        @RequestBody TranscriptionRequestView request) {
        // learnerId is resolved from the JWT by @CurrentUserId and keys the LLM-tier
        // rate limit downstream; the explicit check documents that this surface is
        // ALWAYS per-learner — no anonymous image transcription.
        if (learnerId == null) {
            throw new AnswerInputTranscriptionService.BadRequestException(
                    "authenticated learner required");
        }
        if (request == null) {
            throw new AnswerInputTranscriptionService.BadRequestException(
                    "request body is required");
        }
        AnswerInputTranscriptionService.Transcription result =
                service.transcribe(request.imageBase64(), request.mimeType());
        return TranscriptionView.from(result);
    }

    /** Request: one base64 image (standard alphabet, no data-URL prefix) + its mime type. */
    public record TranscriptionRequestView(String imageBase64, String mimeType) {
    }

    /** Response: the plain text to insert at the caret + which provider read it. */
    public record TranscriptionView(String text, String provider, String model, long latencyMs) {
        static TranscriptionView from(AnswerInputTranscriptionService.Transcription t) {
            return new TranscriptionView(t.text(), t.providerName(), t.model(), t.latencyMs());
        }
    }
}
