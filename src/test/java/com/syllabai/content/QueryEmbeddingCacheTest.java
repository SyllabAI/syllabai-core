package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The query-embedding cache must be invisible when it is wrong: repeat texts
 * embed exactly once per TTL window, distinct texts never cross-pollute,
 * expired entries re-embed, a failed embed is never cached, and no caller can
 * ever hold (or mutate) a shared mutable vector.
 */
class QueryEmbeddingCacheTest {

    /** controllable nano clock — TTL expiry is pinned without wall-clock waits */
    private final long[] now = {0};
    private final QueryEmbeddingCache cache = new QueryEmbeddingCache(() -> now[0]);

    @Test
    @DisplayName("a repeat query embeds exactly once within the TTL window — and clones keep arrays independent")
    void repeatedQueryEmbedsOnceWithinTtl() {
        AtomicInteger calls = new AtomicInteger();
        float[] first = cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.1f, 0.2f};
        });

        float[] second = cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{9.9f, 9.9f};
        });

        assertThat(calls).hasValue(1);   // one Gemini HTTP round trip, not two
        assertThat(second).containsExactly(0.1f, 0.2f);

        // the caller may mutate its own array — the cached generation must not care
        first[0] = 777f;
        assertThat(cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.0f};
        })).containsExactly(0.1f, 0.2f);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("distinct texts embed independently — one key per exact text")
    void distinctTextsEmbedIndependently() {
        AtomicInteger calls = new AtomicInteger();
        cache.getOrEmbed("titration", () -> {
            calls.incrementAndGet();
            return new float[]{1f};
        });
        cache.getOrEmbed("electrolysis", () -> {
            calls.incrementAndGet();
            return new float[]{2f};
        });
        cache.getOrEmbed("titration", () -> {
            calls.incrementAndGet();
            return new float[]{3f};
        });

        assertThat(calls).hasValue(2);   // "electrolysis" was a miss, the repeat of "titration" was not
    }

    @Test
    @DisplayName("an expired entry re-embeds — the TTL is a wholesale generation boundary")
    void expiredEntryReembeds() {
        AtomicInteger calls = new AtomicInteger();
        cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.1f};
        });
        now[0] = QueryEmbeddingCache.TTL_NANOS - 1;
        cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.2f};
        });
        assertThat(calls).hasValue(1);   // one nano inside the window still hits

        now[0] = QueryEmbeddingCache.TTL_NANOS;   // window boundary: wholesale expiry
        float[] fresh = cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.3f};
        });
        assertThat(calls).hasValue(2);
        assertThat(fresh).containsExactly(0.3f);
    }

    @Test
    @DisplayName("a failed embed is never cached — the same key re-embeds after the provider recovers")
    void failureNeverCached() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> cache.getOrEmbed("what is titration?", () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("embedding provider timed out after 30s");
            }
            return new float[]{0.1f};
        })).isInstanceOf(IllegalStateException.class);

        float[] recovered = cache.getOrEmbed("what is titration?", () -> {
            calls.incrementAndGet();
            return new float[]{0.1f};
        });
        assertThat(calls).hasValue(2);
        assertThat(recovered).containsExactly(0.1f);
    }

    @Test
    @DisplayName("the bound is a wholesale memory guard: the miss that trips it clears every earlier key")
    void boundClearsWholesale() {
        AtomicInteger earlyCalls = new AtomicInteger();
        for (int i = 0; i < QueryEmbeddingCache.MAX_ENTRIES; i++) {
            final int idx = i;
            cache.getOrEmbed("query-" + idx, () -> new float[]{idx});
        }
        cache.getOrEmbed("query-0", () -> {
            earlyCalls.incrementAndGet();
            return new float[]{0f};
        });
        assertThat(earlyCalls).hasValue(0);   // hit: the loop's embed is reused — callable skipped

        // this miss trips size >= MAX_ENTRIES: everything already cached is dropped,
        // and the triggering entry is put AFTER the clear (so it survives)
        cache.getOrEmbed("overflow", () -> new float[]{0f});
        cache.getOrEmbed("query-0", () -> {
            earlyCalls.incrementAndGet();
            return new float[]{0f};
        });
        assertThat(earlyCalls).hasValue(1);   // dropped wholesale → re-embeds

        AtomicInteger overflowCalls = new AtomicInteger();
        cache.getOrEmbed("overflow", () -> {
            overflowCalls.incrementAndGet();
            return new float[]{0f};
        });
        assertThat(overflowCalls).hasValue(0);   // the triggering entry itself still hits
    }

    @Test
    @DisplayName("null text never keys the cache — it flows to the callable's own failure shape")
    void nullTextBypassesCache() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> cache.getOrEmbed(null, () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("text must not be null");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.getOrEmbed(null, () -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("text must not be null");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).hasValue(2);   // bypassed twice, no NPE from the map, no caching
    }
}
