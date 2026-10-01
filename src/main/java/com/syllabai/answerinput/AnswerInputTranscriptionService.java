package com.syllabai.answerinput;

import com.syllabai.infrastructure.llm.LlmMedia;
import com.syllabai.infrastructure.llm.LlmProviderException;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import com.syllabai.infrastructure.llm.FailoverLlmChain;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Learner answer-input transcription (HUB-ANSWER-BOX wave 3, operator trace
 * 1a0e9d0b24f445c1 "route (1) adds a core endpoint sounds good"; free/no-card
 * constraint — no new vendor, the existing chain's vision-capable member reads
 * the image).
 *
 * <p><strong>The contract-carrying core of this feature</strong> is the
 * transcription policy below. Under answer format v1 the policy forbade
 * LaTeX outright so the answer stayed plain text; answer format v2 (HUB-ANSWER-BOX
 * wave 4, operator trace 1a0ea6d4ca777a75 "Go on with updating the answer format")
 * upgrades the declared answer dialect to "Markdown with embedded LaTeX math and
 * limited inline HTML" — the same dialect the hub's corpus renderer already
 * interprets — so the model now outputs handwritten MATH as inline LaTeX in
 * $…$ and words as plain text. What lands in the answer editor is therefore a
 * valid v2 answer end-to-end (rich editor renders the math; storage, the 4000
 * cap, and both mark lanes are unchanged). The image is transcription INPUT
 * only; it is never stored, never sent to a mark lane, and leaves no artifact
 * beyond the returned text.</p>
 *
 * <p>Validation posture: mime whitelist + decoded-size cap BEFORE any model call
 * (cost guard); blank model output is a structured "nothing readable" result the
 * web layer maps to 422 — mirroring the SaveMyExams UX the operator benchmarked
 * ("Write text or equations and we'll convert it to text" / NO_TEXT).</p>
 */
@Service
public class AnswerInputTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AnswerInputTranscriptionService.class);

    /** Image types the adapter can map; anything else is rejected pre-call. */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

    /** Decoded image byte cap — matches the client-side downscale (≤1600px, ≤4 MB). */
    static final int MAX_DECODED_BYTES = 4 * 1024 * 1024;

    /**
     * Answer format v2 transcription policy: handwritten math becomes inline
     * LaTeX in $…$ (the hub's rich editor renders it as an equation; the mark
     * lanes read it literally), words stay plain text. No markdown structure
     * (no headings/lists/code fences — the answer editor consumes text and
     * math atoms, not documents). Unicode symbols stay available for inline
     * units and degree signs inside prose.
     */
    static final String TRANSCRIPTION_SYSTEM_PROMPT = """
            You transcribe a photograph or drawing of a student's handwritten work.

            Output rules (absolute):
            - Written MATH goes inside inline LaTeX delimiters: $x^2 + 1$, $\\frac{d}{dx}$,
              $\\ce{H2SO4}$ for chemistry. Every formula, expression, equation, or numeric
              working is math — delimit it.
            - Written WORDS stay plain text outside the delimiters. Do not output markdown
              structure: no headings, lists, bold/italic markers, tables, or code fences.
            - Simple inline symbols in prose may stay Unicode: ² ³ ° ≤ ≥ × ± → ⇌.
            - Transcribe only what the student actually wrote. Preserve their wording and
              their working order; do not correct, complete, solve, or add anything.
            - If parts are unreadable, transcribe the readable parts and mark each unreadable
              spot with [?].
            - If the image contains no handwritten work at all, output exactly: [empty]
            """;

    private static final String USER_PROMPT =
            "Transcribe the handwritten work in this image following the rules.";

    /** Chain temperature 0 — transcription is a read-back, not a generation. */
    private static final Double TEMPERATURE = 0.0;
    private static final Integer MAX_TOKENS = 700;

    private final FailoverLlmChain chain;

    public AnswerInputTranscriptionService(FailoverLlmChain chain) {
        this.chain = chain;
    }

    /** Validated transcription result — text is guaranteed non-blank, [empty]-free. */
    public record Transcription(String text, String providerName, String model, long latencyMs) {
    }

    public Transcription transcribe(String base64Image, String mimeType) {
        if (base64Image == null || base64Image.isBlank()) {
            throw new BadRequestException("image is required");
        }
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new BadRequestException(
                    "unsupported image type (allowed: png, jpeg, webp): " + mimeType);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Image);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("image payload is not valid base64");
        }
        if (decoded.length == 0) {
            throw new BadRequestException("image payload is empty");
        }
        if (decoded.length > MAX_DECODED_BYTES) {
            throw new ImageTooLargeException(decoded.length, MAX_DECODED_BYTES);
        }

        LlmRequest request = LlmRequest.withOptions(
                        TRANSCRIPTION_SYSTEM_PROMPT, USER_PROMPT, TEMPERATURE, MAX_TOKENS)
                .withMedia(new LlmMedia(base64Image, mimeType));
        LlmResponse response;
        try {
            response = chain.generate(request);
        } catch (LlmProviderException e) {
            log.warn("answer-input transcription unavailable: {}", e.getMessage());
            throw new TranscriptionUnavailableException(
                    "transcription service is unavailable right now, try again shortly");
        }
        String text = response.text() == null ? "" : response.text().trim();
        if (text.isEmpty() || "[empty]".equalsIgnoreCase(text)) {
            throw new NothingReadableException();
        }
        return new Transcription(text, response.providerName(), response.model(), response.latencyMs());
    }

    // ── exceptions mapped at the web layer ──────────────────────────────────────

    /** 400 — malformed request (bad mime, bad base64, empty payload). */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** 413 — decoded image over the size cap. */
    public static class ImageTooLargeException extends RuntimeException {
        public ImageTooLargeException(int actualBytes, int maxBytes) {
            super("image is " + actualBytes + " bytes, over the " + maxBytes + " byte cap — "
                    + "downscale it and try again");
        }
    }

    /** 503 — chain exhausted (no vision-capable provider / all failed). */
    public static class TranscriptionUnavailableException extends RuntimeException {
        public TranscriptionUnavailableException(String message) {
            super(message);
        }
    }

    /** 422 — the model read the image but found no handwritten work. */
    public static class NothingReadableException extends RuntimeException {
        public NothingReadableException() {
            super("we couldn't read any handwriting in that image — write clearly and try again");
        }
    }
}
