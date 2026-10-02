package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit pins for the raw-trail keyset cursor (T-C61): round-trip fidelity at
 * full precision (the microsecond band is exactly where an epoch-millis
 * cursor would break strict keyset semantics), URL-safe wire form, and
 * fail-closed decoding — a malformed cursor throws, the endpoint never
 * guesses around damaged server-issued state.
 */
class TrailCursorTest {

    @Test
    @DisplayName("encode → decode round-trips the exact instant and id")
    void roundTrip() {
        Instant occurredAt = Instant.parse("2026-10-02T09:45:24.123456Z");
        UUID id = UUID.fromString("0b9e6c1d-7f2a-4c3e-9d1a-5f6e7a8b9c0d");
        String cursor = TrailCursor.encode(occurredAt, id);
        TrailCursor.Position pos = TrailCursor.decode(cursor);
        assertThat(pos.occurredAt()).isEqualTo(occurredAt);
        assertThat(pos.id()).isEqualTo(id);
    }

    @Test
    @DisplayName("nanosecond precision survives the round trip (no epoch-millis truncation)")
    void subMilliPrecisionSurvives() {
        Instant occurredAt = Instant.ofEpochSecond(1_792_000_000L, 123_456_789L);
        String cursor = TrailCursor.encode(occurredAt, UUID.randomUUID());
        assertThat(TrailCursor.decode(cursor).occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("cursor is URL-safe base64url, unpadded — safe in a query string")
    void urlSafeWireForm() {
        String cursor = TrailCursor.encode(Instant.now(), UUID.randomUUID());
        assertThat(cursor).doesNotContain("+", "/", "=");
    }

    @Test
    @DisplayName("decode is fail-closed: blank, bad base64, wrong shape, bad instant, bad uuid")
    void failClosedDecode() {
        assertThatThrownBy(() -> TrailCursor.decode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrailCursor.decode("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrailCursor.decode("not-base64!!"))
                .isInstanceOf(IllegalArgumentException.class);
        // valid base64url, wrong JSON shape
        String wrongShape = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"x\":1}".getBytes());
        assertThatThrownBy(() -> TrailCursor.decode(wrongShape))
                .isInstanceOf(IllegalArgumentException.class);
        // right shape, unparseable instant
        String badInstant = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"t\":\"yesterday\",\"i\":\""
                        + UUID.randomUUID() + "\"}").getBytes());
        assertThatThrownBy(() -> TrailCursor.decode(badInstant))
                .isInstanceOf(IllegalArgumentException.class);
        // right shape, unparseable id
        String badId = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"t\":\"" + Instant.now() + "\",\"i\":\"nope\"}").getBytes());
        assertThatThrownBy(() -> TrailCursor.decode(badId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("encode rejects a null position component — never a half cursor")
    void encodeRejectsNulls() {
        assertThatThrownBy(() -> TrailCursor.encode(null, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrailCursor.encode(Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
