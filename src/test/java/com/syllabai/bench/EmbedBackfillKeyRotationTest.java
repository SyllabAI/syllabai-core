package com.syllabai.bench;

import com.syllabai.content.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-key rotation semantics for the embed-backfill runner (bench-only;
 * continuation of the session-92 lane). Driven by the 2026-09-17
 * ops-embed-backfill dispatch failures: Google's daily-quota 429 wording was
 * mis-classified as retryable, so a single exhausted key burned the whole
 * 12-retry ladder (~25 min) before INCOMPLETE, and there was no way to pool
 * quota across several keys from distinct projects.
 */
class EmbedBackfillKeyRotationTest {

    private static final float[] VEC = new float[768];

    /**
     * Fake provider: throws a RuntimeException carrying a scriptable failure
     * message for the first {@code failFirst} calls, then returns the vector.
     * The RatePacer must classify daily-quota 429 and project-denied 403
     * wordings as key-level and surface EmbeddingRateException immediately.
     */
    private static final class ScriptedProvider implements EmbeddingProvider {
        private static final String DAILY_QUOTA_429 = "embedding call failed: 429 . You exceeded your "
                + "current quota, please check your plan and billing details. For more "
                + "information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits";
        private final float[] vector;
        private final int failFirst;
        private final String failMessage;
        private int calls = 0;

        ScriptedProvider(float[] vector, int failFirst) {
            this(vector, failFirst, DAILY_QUOTA_429);
        }

        ScriptedProvider(float[] vector, int failFirst, String failMessage) {
            this.vector = vector;
            this.failFirst = failFirst;
            this.failMessage = failMessage;
        }

        @Override
        public String model() {
            return "fake";
        }

        @Override
        public int dimension() {
            return vector.length;
        }

        @Override
        public float[] embedDocument(String text) {
            calls++;
            if (calls <= failFirst) {
                throw new IllegalStateException(failMessage);
            }
            return vector;
        }

        @Override
        public float[] embedQuery(String text) {
            return embedDocument(text);
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(this::embedDocument).toList();
        }
    }

    @Test
    void parsesMultiKeyEnvWithSeparatorsAndDedup() {
        assertEquals(List.of("k1", "k2", "k3"),
                EmbedBackfill.parseKeys("k1, k2;k3\nk1  k2", null));
    }

    @Test
    void fallsBackToLegacySingleKeyAndBlankMeansEmpty() {
        assertEquals(List.of("solo"), EmbedBackfill.parseKeys(null, "solo"));
        assertTrue(EmbedBackfill.parseKeys("  ", "  ").isEmpty());
    }

    @Test
    void classifiesObservedDailyQuotaWordingAsQuotaExhausted() {
        assertTrue(EmbedBackfill.isQuotaExhausted("429 . You exceeded your current quota, "
                + "please check your plan and billing details."));
        assertTrue(EmbedBackfill.isQuotaExhausted("daily quota exhausted at chunk x:1"));
        assertTrue(EmbedBackfill.isQuotaExhausted("Quota exceeded: RPD limit reached"));
        assertFalse(EmbedBackfill.isQuotaExhausted("429 too many requests"));
        assertFalse(EmbedBackfill.isQuotaExhausted("embedding provider timed out after 30s"));
        assertFalse(EmbedBackfill.isQuotaExhausted("Resource has been exhausted (e.g. check quota)."));
        assertFalse(EmbedBackfill.isQuotaExhausted(null));
    }

    /** Run 7 (35240141258) killed the whole dispatch: 403 project-denied hit the fatal branch. */
    private static final String PROJECT_DENIED_403 = "403 . Your project has been denied access. "
            + "Please contact support.";

    @Test
    void classifiesObservedDeniedAccessWordingAsKeyDenied() {
        assertTrue(EmbedBackfill.isKeyDenied(PROJECT_DENIED_403));
        assertTrue(EmbedBackfill.isKeyDenied("API key not valid. Please pass a valid API key."));
        assertTrue(EmbedBackfill.isKeyDenied("api_key_invalid: the key is revoked"));
        assertTrue(EmbedBackfill.isKeyDenied("403 The consumer has been suspended."));
        assertFalse(EmbedBackfill.isKeyDenied("429 . You exceeded your current quota"));
        assertFalse(EmbedBackfill.isKeyDenied("embedding provider timed out after 30s"));
        assertFalse(EmbedBackfill.isKeyDenied("403 unknown refusal wording stays fatal"));
        assertFalse(EmbedBackfill.isKeyDenied(null));
    }

    @Test
    void rotatesToNextKeyOnProjectDeniedAccessAndCountsPerKey() throws Exception {
        EmbeddingProvider denied = new ScriptedProvider(null, Integer.MAX_VALUE, PROJECT_DENIED_403);
        EmbeddingProvider live = new ScriptedProvider(VEC, 0);
        List<EmbeddingProvider> providers = List.of(denied, live);
        int[] perKey = new int[providers.size()];
        float[] v = EmbedBackfill.embedRotating(providers,
                i -> () -> providers.get(i).embedDocument("doc:1"),
                new EmbedBackfill.RatePacer(0), "chunk test:11", i -> perKey[i]++);
        assertEquals(768, v.length);
        assertEquals(0, perKey[0]);
        assertEquals(1, perKey[1]);
    }

    @Test
    void allKeysDeniedSurfacesRateExceptionForIncompleteDump() {
        List<EmbeddingProvider> providers = List.of(
                new ScriptedProvider(null, Integer.MAX_VALUE, PROJECT_DENIED_403),
                new ScriptedProvider(null, Integer.MAX_VALUE, "API key not valid. Please pass a valid API key."));
        assertThrows(EmbedBackfill.EmbeddingRateException.class, () ->
                EmbedBackfill.embedRotating(providers,
                        i -> () -> providers.get(i).embedDocument("doc:1"),
                        new EmbedBackfill.RatePacer(0), "chunk test:12", i -> { }));
    }

    @Test
    void rotatesToNextKeyOnQuotaExhaustionAndCountsPerKey() throws Exception {
        EmbeddingProvider dead = new ScriptedProvider(null, Integer.MAX_VALUE);
        EmbeddingProvider live = new ScriptedProvider(VEC, 0);
        List<EmbeddingProvider> providers = List.of(dead, live);
        int[] perKey = new int[providers.size()];
        float[] v = EmbedBackfill.embedRotating(providers,
                i -> () -> providers.get(i).embedDocument("doc:1"),
                new EmbedBackfill.RatePacer(0), "chunk test:1", i -> perKey[i]++);
        assertEquals(768, v.length);
        assertEquals(0, perKey[0]);
        assertEquals(1, perKey[1]);
    }

    @Test
    void rethrowsAfterLastKeyExhausted() {
        List<EmbeddingProvider> providers = List.of(
                new ScriptedProvider(null, Integer.MAX_VALUE),
                new ScriptedProvider(null, Integer.MAX_VALUE));
        assertThrows(EmbedBackfill.EmbeddingRateException.class, () ->
                EmbedBackfill.embedRotating(providers,
                        i -> () -> providers.get(i).embedDocument("doc:1"),
                        new EmbedBackfill.RatePacer(0), "chunk test:2", i -> { }));
    }

    @Test
    void singleProviderFailurePropagatesWithoutRotation() {
        EmbeddingProvider dead = new ScriptedProvider(null, Integer.MAX_VALUE);
        assertThrows(EmbedBackfill.EmbeddingRateException.class, () ->
                EmbedBackfill.embedRotating(List.of(dead),
                        i -> () -> dead.embedDocument("doc:1"),
                        new EmbedBackfill.RatePacer(0), "chunk test:3", i -> { }));
    }
}
