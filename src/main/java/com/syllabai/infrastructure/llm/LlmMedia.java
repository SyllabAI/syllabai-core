package com.syllabai.infrastructure.llm;

import java.util.Base64;

/**
 * Binary media content attached to an {@link LlmRequest} (HUB-ANSWER-BOX wave 3 —
 * learner answer-input transcription). Carrier for one image the vision-capable
 * chain member should read; everything provider-specific (SDK classes, part
 * building) stays inside the adapter (§26 — no provider API types cross the port).
 *
 * @param base64Data raw image bytes, base64-encoded (standard alphabet, no data-URL
 *                   prefix — the controller layer strips/validates that)
 * @param mimeType   an image mime type the adapter can map (image/png, image/jpeg,
 *                   image/webp)
 */
public record LlmMedia(String base64Data, String mimeType) {

    /** Decoded byte length — the controller/service layer enforces size caps. */
    public int decodedByteLength() {
        return Base64.getDecoder().decode(base64Data).length;
    }
}
