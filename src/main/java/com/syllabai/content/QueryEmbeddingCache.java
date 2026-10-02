package com.syllabai.content;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-process cache for QUERY embedding vectors, keyed by the exact query text
 * (CLA latency ledger follow-up, audit 2026-10-02: every tutor ask, CLA ask
 * and content-search hit paid one Gemini {@code embedQuery} HTTP round trip —
 * 30s timeout budget on the serving path — for a vector that is a pure
 * function of (model, task type, text)).
 *
 * <p>Semantics: an embedding is deterministic for a fixed model, so unlike a
 * curriculum read model there is NO freshness surface — a cached query vector
 * is exactly as valid minutes later as it was on the miss. The TTL is a
 * memory/quota guard, not a staleness bound; it is deliberately longer than
 * the structure/tree snapshots (repeat asks arrive minutes apart — a 30s TTL
 * would gut the hit rate while the free tier pays the quota for every
 * re-embed). Constant, non-tunable, wholesale expiry.</p>
 *
 * <p>Shape: a bounded concurrent map (256 entries ≈ 768-dim float vectors →
 * well under 1 MB), wholesale clear past the bound (a re-embed is one HTTP
 * call — the bound is a memory guard, not an eviction policy). Stored and
 * returned arrays are defensively cloned: callers must never be able to hold
 * — or mutate — a shared mutable vector. Failed embeds are never cached: the
 * callable throws before any put, so a flaky Gemini call cannot poison a key
 * and the next ask re-embeds. Concurrent missers may both embed — the vector
 * is idempotent and the loser's copy is simply discarded. A {@code null} text
 * never keys the cache (it flows straight to the callable, which throws the
 * caller's own shape).</p>
 *
 * <p>Deliberately NOT used for {@code embedDocument}/{@code embedDocuments}:
 * indexing embeds unique chunk texts — a ~0 hit-rate cache there would be
 * pure memory waste on the backfill path.</p>
 */
final class QueryEmbeddingCache {

    /** 10 min: repeat asks arrive minutes apart; vectors never go stale for a fixed model. */
    static final long TTL_NANOS = 600_000_000_000L;

    /** Memory guard, not an eviction policy: 256 × ~3 KB ≈ well under 1 MB. */
    static final int MAX_ENTRIES = 256;

    /** One immutable cache generation: the stored vector (already cloned) + when it was read. */
    private record CachedVector(float[] vector, long nanos) {
    }

    private final ConcurrentHashMap<String, CachedVector> vectors = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    QueryEmbeddingCache() {
        this(System::nanoTime);
    }

    /** Test seam: a controllable clock so TTL expiry is pinnable without wall-clock waits. */
    QueryEmbeddingCache(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Returns the embedding for {@code text}, calling {@code embed} exactly
     * once per (key, TTL window). The callable runs only on a miss; whatever
     * it throws propagates and nothing is cached.
     */
    float[] getOrEmbed(String text, Callable<float[]> embed) {
        if (text == null) {
            return call(embed);
        }
        long now = clock.getAsLong();
        CachedVector hit = vectors.get(text);
        if (hit != null && now - hit.nanos() < TTL_NANOS) {
            return hit.vector().clone();
        }
        float[] fresh = call(embed);
        if (vectors.size() >= MAX_ENTRIES) {
            vectors.clear();   // wholesale: a re-embed is one HTTP call
        }
        vectors.put(text, new CachedVector(fresh.clone(), now));
        return fresh;
    }

    private static float[] call(Callable<float[]> embed) {
        try {
            return embed.call();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("query embedding failed", e);
        }
    }
}
