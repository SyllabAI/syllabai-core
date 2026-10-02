package com.syllabai.learner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor for the bounded raw-trail read (T-C61 — GET
 * /api/v1/learners/me/flashcard-rating-trail). The trail's natural order is
 * {@code (occurred_at DESC, id DESC)}: V47's {@code id} is a random UUID, so
 * it cannot order pages by itself and a pure offset page would skip or
 * duplicate rows whenever a concurrent append shifts the window (append-only
 * trail + newest-first read = every page shift inserts rows at the front).
 * The keyset position is therefore the pair (occurred_at, id) of the LAST row
 * of the previous page, encoded opaquely.
 *
 * <p>Encoding contract (pinned by {@code TrailCursorTest}):</p>
 * <ul>
 *   <li>base64url (unpadded) of a two-field JSON object: {@code t} = the
 *       row's occurred_at as an ISO-8601 string, {@code i} = the row id.
 *       ISO-8601 (not epoch millis) so the cursor round-trips the EXACT
 *       instant the database returned — Postgres keeps microseconds, an
 *       epoch-millis cursor would truncate them and break strict keyset
 *       semantics inside the sub-milli band.</li>
 *   <li>FAIL-CLOSED decode: any malformed input (bad base64, wrong JSON
 *       shape, unparseable instant, unparseable UUID) throws — the
 *       controller maps that to 400. A cursor is server-issued state; the
 *       endpoint never guesses around a damaged one.</li>
 *   <li>The payload carries nothing but the position: no learner id, no
 *       filter state — the learner scope comes from the authenticated
 *       request, so a cursor lifted from another account is merely a valid
 *       position in THIS learner's trail (no leakage either way; the learner
 *       clause is never cursor-sourced).</li>
 * </ul>
 *
 * <p>Pure and stateless (the BktEngine pattern): no Spring wiring, no I/O.</p>
 */
public final class TrailCursor {

    /** A keyset position: the (occurred_at, id) of a trail row. */
    public record Position(Instant occurredAt, UUID id) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TrailCursor() {
        // static codec
    }

    /** Encode a keyset position into the opaque wire form. */
    public static String encode(Instant occurredAt, UUID id) {
        if (occurredAt == null || id == null) {
            throw new IllegalArgumentException("cursor position needs occurredAt and id");
        }
        ObjectNode node = MAPPER.createObjectNode();
        // Instant.toString() is ISO-8601 with the exact precision held
        node.put("t", occurredAt.toString());
        node.put("i", id.toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Decode an opaque cursor into its keyset position.
     *
     * @throws IllegalArgumentException on any malformed input — the caller
     *         (the controller) maps this to 400 Bad Request, never a guess
     */
    public static Position decode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("cursor is blank");
        }
        JsonNode node;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            node = MAPPER.readTree(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("cursor is not decodable", e);
        }
        if (node == null || !node.isObject() || node.size() != 2
                || !node.hasNonNull("t") || !node.hasNonNull("i")) {
            throw new IllegalArgumentException("cursor is not a trail position");
        }
        Instant occurredAt;
        UUID id;
        try {
            occurredAt = Instant.parse(node.get("t").asText());
            id = UUID.fromString(node.get("i").asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cursor position is not parseable", e);
        }
        return new Position(occurredAt, id);
    }
}
